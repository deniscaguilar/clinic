# Tarefas: M0 · Fundação (identidade, tenancy, branding e backoffice)

- **Plano:** `plan.md` (status: Aprovada)
- **Status:** Aprovada

Regra: cada tarefa começa pelo teste (vermelho), depois a implementação (verde), depois refatoração. Marque ao concluir com `./mvnw verify` verde. Cada fatia termina com `./mvnw verify` verde e a spec atualizada se a realidade divergiu. Se o código precisar divergir do plano, pare e atualize o plano antes.

Convenções: código, pacotes, pastas e testes em inglês; documentação e commits em português. Toda entidade nova de negócio inclui o teste de isolamento de tenant (CA10). Nenhuma dependência fora da lista aprovada na seção 9.2 do plano.

## S1. Esqueleto, banco, RLS, auditoria (RF1, RF8, RF9; CA3, CA8, CA9, CA10)

- [x] T1.1. Verificar a compatibilidade do Spring Modulith 2.1.x com o Boot 4.1.x e reconferir versões e imagens (Boot, Kotlin, Modulith, Testcontainers, Flyway, MockK, dependency-check; postgres, localstack, mock-oauth2-server, LGTM). Conferir a licença/token do LocalStack 2026.10.3.
  - Teste: projeto mínimo com `@ApplicationModuleTest` sobe; resultado e versões finais registrados no `progress-log.md`.
  - Se o Modulith for incompatível ou o LocalStack exigir licença: **parar e perguntar**.
- [x] T1.2. Subir o Compose (`docker/compose.yaml`) pela primeira vez e corrigir o que falhar; incluir o script de init que cria `clinic_migrator` e `clinic_app` (sem `BYPASSRLS`).
  - Teste: `docker compose up -d` saudável; conexão com cada papel funciona; `clinic_app` não é dona de tabela.
- [x] T1.3. Gerar o projeto Maven (`backend/`, wrapper, Kotlin, JDK 25), `maven-enforcer-plugin` (JDK 25, convergência), JaCoCo com portão ≥ 85% em `domain` + `application`, Failsafe, SBOM CycloneDX, OWASP dependency-check.
  - Teste: `./mvnw verify` verde no esqueleto; build falha com convergência quebrada (verificado uma vez).
- [x] T1.4. Estrutura de módulos (`platform`, `registry`, `notifications`) com pacotes `api/domain/application/adapter/config` e ArchUnit + `Modulith.verify()` (regras do CLAUDE.md, seção 3, incluindo `adapter` sem acesso a schema de outro módulo).
  - Teste: testes de arquitetura; uma violação plantada de propósito reprova e é removida.
- [x] T1.5. Duas `DataSource`s (migração e aplicação) e uma instância do Flyway por módulo, na ordem `platform`, `registry`, `notifications`, `eventing`; histórico no schema do módulo.
  - Teste (`*IT`): Testcontainers sobe, migrações rodam com `clinic_migrator`, a aplicação conecta com `clinic_app`.
- [x] T1.6. **Prova de conceito do contexto de tenant:** `TenantAwareTransactionManager` com `set_config(..., true)` para `app.tenant_id`, `app.user_sub` e `app.scope`; `TenantScope.runAs`.
  - Teste (`*IT`): sem contexto, zero linhas; o valor não vaza entre transações no mesmo pool (executar muitas transações alternando tenants).
  - Se falhar no Hibernate 7: aplicar a alternativa `ConnectionProvider` do plano (9.1) com o mesmo teste; se também falhar, **parar e perguntar**.
- [x] T1.7. Tabela de exemplo/infra de RLS: macro/convenção de migração para `ENABLE` + `FORCE` + política padrão, e o **teste de catálogo** que reprova tabela com `tenant_id` sem RLS habilitada e forçada e sem política.
  - Teste (`*IT`): catálogo verde; tabela temporária sem RLS faz o teste falhar (CA10).
- [x] T1.8. `TenantIsolationIT` genérico por tabela com `tenant_id`: sem contexto zero linhas; tenant A não lê nem altera linha de B; `INSERT` de outro tenant recusado pelo `WITH CHECK`. Teste de que `app.scope = platform` não abre tabelas de paciente.
  - Teste: o próprio `*IT` (parametrizado pelo catálogo).
- [x] T1.9. Segurança: resource server OIDC genérico (issuer/JWKS por configuração), valida assinatura, `iss`, `exp`, `aud`; `sub` como identidade; 401 sem token. CORS restrito, cabeçalhos de segurança, sem sessão.
  - Teste (`*IT` com `mock-oauth2-server`): token válido passa; ausente, inválido e expirado dão 401 (CA3).
- [x] T1.10. Filtro de `X-Clinic-Id` (obrigatório fora de `/me`, `/me/clinics` e `/admin/**`), validação contra vínculo ativo e clínica `ACTIVE`, preenchendo `CurrentUser` e `TenantContext`.
  - Teste (unitário + `*IT`): sem vínculo, vínculo inativo ou clínica inativa dão 403; cabeçalho ausente dá erro.
- [x] T1.11. `Authorizer` e catálogo de permissões: interface `PermissionContributor` em `platform.api`, catálogo montado no startup, 403 com auditoria `DENIED`.
  - Teste (unitário): catálogo reúne contribuições; permissão desconhecida é rejeitada.
- [x] T1.12. Auditoria: tabela `audit_event` (append-only: `clinic_app` só `INSERT`/`SELECT`, `UPDATE`/`DELETE`/`TRUNCATE` revogados, gatilho de rejeição), port `AuditTrail`, gravação na mesma transação, negativas em `REQUIRES_NEW`, `details` só com nomes de campos.
  - Teste (`*IT`): `UPDATE`/`DELETE` com a conexão da aplicação falham; ação revertida ainda deixa o registro `DENIED`/`FAILURE`; falha de auditoria de dado de paciente desfaz a ação (CA8).
- [x] T1.13. Observabilidade: Micrometer/OTLP, filtro que coloca `tenant_id` e `user_id` no trace, endpoints de saúde; regras de log sem dado de paciente.
  - Teste (`*IT`): spans exportados contêm só `tenant_id`, `user_id`, rota e status; teste que procura CPF e e-mail de teste na saída de log e nos spans (zero ocorrências).
- [x] T1.14. Erros em Problem Details (RFC 9457) com `code` estável e sem eco do valor rejeitado; springdoc gerando `backend/openapi/openapi.yaml` versionado, com comparação no build.
  - Teste (`*IT`): formato do erro; falha se o contrato gerado divergir do versionado.
- [x] T1.15. Fechar a S1: `./mvnw verify` verde partindo do `docker compose up` sem internet além das dependências (CA9). Atualizar `progress-log.md`.

## S2. Clínicas, usuários, perfis e permissões (RF2, RF3, RF4, RF4a; CA4, CA5, CA11, CA12)

- [x] T2.1. Migrações e entidades de `platform`: `clinic`, `app_user`, `platform_admin`, `membership`, `role`, `role_permission`, `membership_role` com RLS (inclui a política de `membership` pelo `user_sub` e pelo escopo `platform`).
  - Teste: `TenantIsolationIT` e teste de catálogo cobrem as tabelas novas (CA10).
- [x] T2.2. Port `IdentityProvider` com adaptador falso (`sub` determinístico, alinhado ao `oidc-config.json`) e adaptador Cognito (`AdminCreateUser`, `AdminDisableUser`).
  - Teste: unitário do adaptador Cognito com cliente simulado; o falso gera `sub` estável.
- [x] T2.3. `ListMyClinics`, `ResolveSession` e endpoints `GET /me`, `/me/clinics`, `/session`; auditoria de `LOGIN`.
  - Teste: unitário do caso de uso; `*IT` com usuário em duas clínicas vê só a escolhida (CA11).
- [x] T2.4. `CreateClinic` (`PLATFORM_ADMIN`) com fluxo de convite em duas transações, perfis-modelo (`CLINIC_ADMIN`, `DOCTOR`, `NURSE`, `MANAGER`, `STAFF`), branding padrão e evento `ClinicCreated`.
  - Teste: `DOCTOR` recebe 403 (CA4); `PLATFORM_ADMIN` cria e o 1º admin entra (CA5); falha no `IdentityProvider` deixa vínculo `PENDING`.
- [x] T2.5. `UpdateClinic`, `DeactivateClinic`, `ActivateClinic` e `ResendInvitation` do 1º admin.
  - Teste: clínica `INACTIVE` bloqueia todos os vínculos e preserva os dados; reenvio só para `PENDING`.
- [x] T2.6. `InviteUser`, `UpdateUserRoles`, `DeactivateMembership`, `ResendInvitation` (`MANAGE_USERS`).
  - Teste: e-mail já existente em outra clínica reaproveita o login e a resposta não revela isso; não remove o último `CLINIC_ADMIN` ativo.
- [x] T2.7. `CreateRole`, `UpdateRole`, `DeleteRole` (`MANAGE_ROLES`) e `GET /permissions`.
  - Teste: só aceita permissões do catálogo; perfil em uso não é apagado; permissão exclusiva de plataforma não é atribuível; usuário com perfil restrito recebe 403 fora das permissões (CA12).
- [x] T2.8. `QueryAuditTrail` (`VIEW_AUDIT_TRAIL`) e `GET /audit-events` com filtros e paginação (padrão 20, máximo 100).
  - Teste (`*IT`): só devolve eventos da clínica ativa.
- [x] T2.9. Fechar a S2: `./mvnw verify` verde; OpenAPI atualizado; spec atualizada se divergiu.

## S3. Branding e armazenamento de arquivos (RF7; CA6, CA7)

- [x] T3.1. `Branding`, `HexColor` e `ContrastPolicy` (WCAG ≥ 4,5 com o melhor texto, branco ou preto; calcula `on_primary`).
  - Teste (unitário): casos de borda do 4,5, cores claras legítimas, hex inválido.
- [x] T3.2. Migração e persistência de `tenant_branding` com RLS; `UpdateBranding` (`MANAGE_BRANDING`) e `GET/PUT /branding`.
  - Teste: contraste ruim rejeitado com `BRAND_CONTRAST_INSUFFICIENT` (CA7); cores distintas por clínica (CA6); isolamento de tenant.
- [x] T3.3. Port `FileStorage` com adaptador S3 (SSE, URL assinada curta) e S3 no LocalStack.
  - Teste (`*IT` com LocalStack): grava, lê por URL assinada, URL expira.
- [x] T3.4. `UploadBrandAsset` e `PUT /branding/logo|favicon`: valida tipo pelo conteúdo, tamanho e dimensões; PNG/JPEG/WebP; **sem SVG**.
  - Teste: SVG, extensão mentirosa e arquivo grande rejeitados; arquivo válido entra no branding.
- [x] T3.5. Fechar a S3: `./mvnw verify` verde; OpenAPI atualizado.

## S4. `registry`: profissional, procedimento e paciente (RF4, RF5, RF6; CA1, CA2, CA13, CA14)

- [x] T4.1. Value objects `Cpf` (dígitos verificadores, rejeita sequências repetidas), `Email`, `Phone`, `Address` (CEP, UF válida).
  - Teste (unitário): válidos, inválidos e bordas.
- [x] T4.2. `Patient` (invariantes do RF5: nome completo, CPF, nascimento no passado, endereço, ao menos um contato) com `Clock` injetado.
  - Teste (unitário): cada invariante violada isoladamente.
- [x] T4.3. `Procedure`, `CreateProcedure`/`UpdateProcedure` (`MANAGE_PROCEDURES`), nome único por clínica, `CRUD /procedures`, migração com RLS.
  - Teste: nome duplicado na mesma clínica rejeitado, em outra clínica aceito; isolamento (404 por ID de outra clínica).
- [x] T4.4. `Practitioner` e `WorkSchedule` (intervalo por dia da semana, sem sobreposição, `CHECK` no banco), `RegisterPractitioner`/`UpdatePractitioner` (`MANAGE_PRACTITIONERS`), vínculo de usuário via `platform.api`, `CRUD /practitioners`.
  - Teste: sobreposição rejeitada (unitário e `CHECK`); médico em duas clínicas tem um profissional por clínica com horário próprio (CA11); isolamento.
- [x] T4.5. Migração `patient` com `UNIQUE (tenant_id, cpf)`, RLS e índices; adaptador de persistência com mapeamento explícito domínio ↔ JPA.
  - Teste: isolamento (CA1, CA2); `INSERT` de CPF duplicado na mesma clínica falha, em outra clínica passa.
- [x] T4.6. `RegisterPatient` (nesta fatia, sem consentimento ainda; o aceite dos tipos obrigatórios entra em T5.5), `UpdatePatient` (CPF só muda com permissão e audita), `GetPatient`, `ListPatients`, `SearchPatientByCpf` (`POST /patients/search`) com auditoria de leitura (listagem = um evento com contagem; individual = um por paciente).
  - Teste: campos obrigatórios e contato ausentes dão erro e nada é gravado (CA13); CPF inválido ou duplicado (CA14, `CPF_INVALID`, `CPF_ALREADY_REGISTERED`); CPF nunca em URL; erro sem eco do valor; usuário da clínica A lista só pacientes da A (CA1).
- [x] T4.7. Fechar a S4: `./mvnw verify` verde; OpenAPI atualizado.

## S5. Consentimento e `notifications` (RF5a, RF11 a RF13; CA15 a CA18)

- [x] T5.1. Domínio de consentimento: `ConsentType`, `ConsentVersion` (número crescente, texto imutável), `ConsentGrant` (revogação preenche `revokedAt`/`revokedBy`, não apaga); apenas um tipo por clínica com `controls_messaging`.
  - Teste (unitário): nova versão em vez de edição; regra do tipo único de mensagens.
- [x] T5.2. Migrações `consent_type`, `consent_type_version` (imutável por gatilho), `consent_grant`, `consent_template` (sem tenant; escrita só no escopo `platform`), com RLS.
  - Teste: isolamento; atualizar versão existente falha no banco; escopo `platform` não abre `consent_grant`.
- [x] T5.3. `CreateConsentType`/`ReviseConsentType` (`MANAGE_CONSENT_TYPES`), `ImportConsentTemplate` e `CRUD /consent-types`, `/consent-types/import-template`; `CRUD /admin/consent-templates` (`PLATFORM_ADMIN`).
  - Teste: revisar o texto cria nova versão e aceites anteriores continuam na versão antiga (CA15); importação copia o modelo.
- [x] T5.4. `AcceptConsent`, `RevokeConsent` (`RECORD_CONSENT`), `GET/POST /patients/{id}/consents`, `POST .../revoke`; eventos `ConsentAccepted` e `ConsentRevoked` (só ids) persistidos pelo registro do Modulith (schema `eventing`).
  - Teste: aceite liga à versão vigente e registra instante, canal `IN_CLINIC` e usuário (CA16, parte do registro); revogação preserva o histórico.
- [x] T5.5. Integrar o aceite no `RegisterPatient`: exige **todos** os tipos obrigatórios e grava paciente e aceites na mesma transação; senão nada é gravado.
  - Teste: falta de consentimento obrigatório não grava nada (CA13); caminho feliz grava paciente e aceites.
- [x] T5.6. APIs de `registry` para `notifications`: `HasActiveMessagingConsent` e `GetPatientContact` (devolve só o necessário).
  - Teste: unitário; sem consentimento de mensagens devolve falso; contato mínimo.
- [x] T5.7. `notifications`: domínio `Notification` e política de retentativa (backoff exponencial, máximo de tentativas), migração `notification` (sem destinatário nem texto) com RLS, listeners `OnConsentAccepted` (uma linha por canal cadastrado) e `OnConsentRevoked` (pendentes viram `CANCELLED`).
  - Teste: unitário da política de retentativa; `*IT` do listener; isolamento.
- [x] T5.8. Ports `EmailSender` (SES; LocalStack) e `WhatsAppSender` (falso que registra sem destinatário); `PatientContactLookup` e `MessagingConsentLookup`.
  - Teste: adaptador de e-mail contra LocalStack SES; o falso de WhatsApp não loga destinatário.
- [x] T5.9. `DispatchPending` (worker, `FOR UPDATE SKIP LOCKED`, perfil `worker`/`all`): checa consentimento antes de enviar (`BLOCKED_NO_CONSENT`), retentativa até `FAILED`; texto mínimo (nome do tipo e data).
  - Teste (`*IT` ponta a ponta com adaptadores locais): aceite envia e-mail e WhatsApp (CA16); revogado não envia e registra a tentativa (CA17); falha forçada mantém o aceite e reenvia depois (CA18); dois workers não pegam a mesma linha.
- [x] T5.10. `ListNotifications` (`VIEW_NOTIFICATIONS`) e `GET /notifications`: canal, status e tentativas, sem conteúdo nem destinatário.
  - Teste: resposta não contém destinatário nem texto; isolamento.
- [x] T5.11. Fechar a S5: `./mvnw verify` verde; `Modulith.verify()` com as dependências do `docs/architecture.md`; OpenAPI atualizado.

## S6. App React (RF10; CA6 no front)

- [x] T6.1. Esqueleto de `app/`: Vite, React, TypeScript, React Router, PWA instalável (service worker só para o shell, **sem cache de resposta da API**), Vitest, Testing Library, Playwright; cliente gerado do OpenAPI (`openapi-typescript`, `openapi-fetch`).
  - Teste: build; cliente regenerado sem diferença contra o contrato versionado.
- [x] T6.2. i18n (`react-i18next`) com chaves em inglês e textos pt-BR; sem texto hardcoded.
  - Teste (Vitest): chave ausente falha o teste.
- [x] T6.3. Login OIDC (Authorization Code + PKCE, `oidc-client-ts`) e escolha de clínica (`X-Clinic-Id`).
  - Teste (Vitest): token anexado, cabeçalho enviado, troca de clínica.
- [x] T6.4. Aplicação do branding por CSS variables (`--color-primary`, `--color-on-primary`, logo, favicon) e navegação por permissões da sessão.
  - Teste (Vitest): clínicas A e B com cores distintas (CA6); item de menu some sem permissão.
- [x] T6.5. Telas de `/admin`: clínicas e modelos de consentimento.
  - Teste (Vitest): formulários, validação e erros por `code`.
- [x] T6.6. Telas da clínica: usuários, perfis, branding, auditoria, profissionais, procedimentos, tipos de consentimento.
  - Teste (Vitest): formulários e estados de erro (inclui contraste insuficiente).
- [x] T6.7. Telas de pacientes: cadastro com aceite dos consentimentos obrigatórios, busca por CPF, aceite e revogação.
  - Teste (Vitest): bloqueio sem consentimento obrigatório; CPF nunca na URL.
- [x] T6.8. Fluxo crítico em Playwright: login, escolha de clínica, cadastro de paciente com consentimento.
  - Teste: o próprio fluxo contra Compose local.
- [x] T6.9. Fechar a S6 e o M0: `./mvnw verify` e testes do front verdes; verificar todos os CA1 a CA18 contra os testes; atualizar spec, `progress-log.md` e `docs/roadmap.md` (cópia em `/Users/denis/EndoAcademy/roadmap/`).
- [x] T6.10. Tela de notificações (`/notifications`): lista com tipo, canal, situação, tentativas, próxima tentativa e erro; filtros por situação e canal; paginação; paciente só por id.
  - Teste (Vitest): lista, filtros enviados, estados vazio e de erro por `code`.
- [x] T6.11. Edição de paciente (`/patients/:id/edit`): formulário compartilhado com o cadastro; CPF somente leitura sem `CHANGE_PATIENT_CPF` e só enviado quando muda.
  - Teste (Vitest): dados carregados, CPF por permissão, validação, erros por `code`.
- [x] T6.12. Login manual no mock OIDC: `docker/oidc/oidc-config.json` casa o mapeamento de claims por `grant_type` (antes por `scope`, que o fluxo de código não envia). Decisão do Denis em 30/10/2026.
  - Teste: Playwright sem o workaround das claims no formulário de login.
- [x] T6.13. Dockerfile do backend (multi-stage, base com digest fixado, usuário sem privilégio) e scan da imagem com Trivy no CI (`.trivyignore.yaml` com justificativa e expiração).
  - Teste: a imagem sobe com health UP; Trivy sem Alta/Crítica.
- [x] T6.14. E2E (Playwright) no CI: o job sobe Postgres, LocalStack e OIDC mock (`docker/compose.yaml`), o backend no perfil `local` e roda o fluxo crítico. Sem custo (Actions em repositório público).
  - Teste: o próprio job.
- [x] T6.15. Chave `clinic.notifications.whatsapp.provider` com falha fechada (hoje só `memory`, o adaptador falso), para trocar pelo provedor real sem esquecer o falso em produção.
  - Teste: provedor desconhecido ou vazio derruba a subida; fiação do adaptador falso.

## Fora das tarefas (por regra)

Nenhum deploy, `apply` de infra nem custo na AWS faz parte do M0 (veja `docs/go-live.md`). O pipeline de CI, o Dockerfile e o Trivy na imagem entraram depois (T6.13 e T6.14); OpenTofu segue em spec própria antes do go-live. Verificação real do Cognito acontece no ambiente de fumaça, antes do go-live.

## Aprovação
- [x] Aprovado por: Denis em 28/10/2026
