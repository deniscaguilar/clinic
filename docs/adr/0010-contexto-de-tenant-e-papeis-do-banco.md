# ADR-0010: Contexto de tenant, papéis do banco e escopo `platform` na RLS

- **Status:** Aceita
- **Data:** 2026-10-28
- **Origem:** plano do M0 (`specs/platform/m0-foundation/plan.md`, seções 5 e 9.2)

## Contexto

O ADR-0007 define RLS por `tenant_id`. Faltam três detalhes que o M0 precisa fixar: como o tenant chega ao banco, com qual papel a aplicação conecta e como o `PLATFORM_ADMIN` opera entre clínicas sem abrir dados de paciente.

## Decisão

**Papéis do banco.** `clinic_migrator` é dono dos schemas e roda o Flyway. `clinic_app` é usado pela aplicação, sem `BYPASSRLS` e sem ser dono de tabela. Os papéis são criados pelo ambiente (init do Compose, provisionamento do RDS); as migrações só fazem `GRANT`. Duas `DataSource`s: migração e aplicação. Toda tabela de negócio tem RLS `ENABLE` e `FORCE`.

**Contexto por transação.** O `TenantAwareTransactionManager` (estende `JpaTransactionManager`) executa `set_config(..., true)` no início de cada transação, então o valor vale só nela. Variáveis:
- `app.tenant_id`: clínica ativa. Política padrão: `tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`, com `USING` e `WITH CHECK`. Sem variável, nenhuma linha (falha fechada).
- `app.user_sub`: resolve os vínculos do próprio usuário antes de existir clínica ativa (só `SELECT` em `membership`).
- `app.scope = platform`: só dentro de casos de uso do `PLATFORM_ADMIN`. Libera apenas `clinic`, `app_user`, `membership`, `audit_event` e `consent_template`. **Nunca** libera tabelas de paciente.

**Clínica ativa.** Vem do cabeçalho `X-Clinic-Id`, validado a cada requisição contra o vínculo do usuário (sem sessão no servidor e sem claim no token). Listeners e workers rodam sob `TenantScope.runAs(tenantId)`, com o tenant vindo do evento.

**Garantias em teste.** Teste de catálogo reprova tabela com `tenant_id` sem RLS habilitada e forçada e sem política. Teste de que `app.scope = platform` não abre tabela de paciente. Teste de vazamento de contexto entre transações no pool.

## Consequências

- Não depende do Cognito para trocar de clínica; troca sem novo login.
- Risco: a obtenção da conexão no início da transação depende do dialeto JPA do Hibernate 7. A primeira tarefa da S1 é uma prova de conceito; alternativa é um `ConnectionProvider` do Hibernate com o mesmo teste.
- Altera RLS e autenticação: exige esta ADR aprovada (CLAUDE.md, seção 10).

## Emenda 1 (29/10/2026): leitura e convite de usuários dentro da clínica

- **Status da emenda:** Aceita. Aplicada no código (migração `V4`); aprovada por Denis em 29/10/2026.
- **Problema (T2.6).** O `CLINIC_ADMIN` convida usuários e precisa reaproveitar o login que o e-mail já tem em outra clínica, sem revelar que ele existia, e ler nome e e-mail dos usuários da própria clínica (reenvio de convite, listagem). A política original deixava `app_user` legível só pelo próprio `sub` e pelo escopo `platform`, e o escopo `platform` é reservado aos casos de uso do `PLATFORM_ADMIN`.
- **Decisão.** Sem abrir o escopo `platform` para a clínica:
  1. `membership.user_sub` guarda o `sub` do usuário quando o vínculo é ativado. A leitura dos próprios vínculos passa a usar essa coluna (`user_sub = app.user_sub`), sem consultar `app_user`; isso evita o ciclo de políticas entre `membership` e `app_user`.
  2. `app_user` passa a ser **legível pela clínica** para os usuários que têm vínculo com ela (a política de `membership` já limita ao tenant ativo).
  3. Dentro de uma clínica, `app_user` aceita **`INSERT` apenas sem `sub`** e **`UPDATE` apenas do `sub` ainda nulo** de um usuário da própria clínica. O `sub` vem do provedor de identidade; o banco nunca aceita um `sub` novo no `INSERT`.
  4. O id do `app_user` é derivado do e-mail normalizado (`UUID.nameUUIDFromBytes("clinic-user:" + email)`). O convite grava com `ON CONFLICT DO NOTHING` num id que se calcula sem consultar, então nada é lido nem revelado quando o e-mail já existe em outra clínica.
- **Consequências.**
  - O escopo `platform` continua só para o `PLATFORM_ADMIN` e continua sem abrir tabelas de paciente.
  - Quem convida pode ver nome e e-mail dos usuários da própria clínica (e só deles).
  - Risco aceito: um `CLINIC_ADMIN` com acesso direto ao banco poderia gravar um `sub` num usuário pendente da própria clínica; a unicidade de `sub` impede duplicar um `sub` existente. Em produção a aplicação é o único cliente do papel `clinic_app`.
  - Todo `app_user` deve ser criado com o id derivado do e-mail; usuários de fixtures de teste com id aleatório não podem coexistir com um convite para o mesmo e-mail.
  - Vínculos anteriores à `V4` (só havia dados de teste) tiveram o `user_sub` preenchido pela própria migração.
- **Testes.** `IdentityRlsIT` cobre cada política da emenda (leitura por tenant, `INSERT` sem `sub`, `ON CONFLICT DO NOTHING` sem vazar, `UPDATE` do `sub` uma única vez, vínculo pendente invisível para o usuário); `UserIT` cobre o reaproveitamento do login com resposta idêntica.

