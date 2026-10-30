# CLAUDE.md: Sistema da Clínica

> **Status:** Aprovada (Dênis, via Plannotator, em 30/10/2026).

Regras obrigatórias para qualquer trabalho de IA neste repositório. Em caso de conflito com um pedido, **pare e pergunte**. As decisões de fundo estão em `docs/adr/`.

> Nome do produto e `groupId` Maven ainda são **provisórios** (`com.endoacademy.clinic`). Não os espalhe por configs externas até serem definidos.

## 1. Contexto

Sistema de gestão clínica com PWA e backoffice web, primeiro para a Clínica da DCA (piloto) e depois para clínicas de mentorados (multi-clínica desde o início). Integra o Endo Academy AI. Desenvolvedor único (Dênis) trabalhando com IA. Roadmap em `docs/roadmap.md`.

## 2. Stack (versões verificadas em 26/10/2026; reconfira ao gerar o `pom.xml`)

| Item | Decisão |
|---|---|
| Linguagem | **Kotlin** na versão do BOM do Boot (2.3.x no Boot 4.1.2; Java só com motivo registrado), `jvmTarget` 25 |
| JDK | **25 (LTS)**, Eclipse Temurin, imagens arm64 |
| Framework | **Spring Boot 4.1.x**, Spring Modulith 2.1.x |
| Build | **Maven** com wrapper (`./mvnw`), um único módulo de build |
| Banco | PostgreSQL (versão major igual à do RDS), Flyway, **RLS** para tenants |
| Testes | JUnit 5, MockK, AssertJ, Testcontainers 2.x, ArchUnit, JaCoCo |
| Auth | OIDC genérico (resource server). **Cognito** em prod, mock OIDC local |
| Frontend | PWA em React + TypeScript (+ backoffice na mesma base), cliente gerado do OpenAPI |
| Infra | AWS **sa-east-1**, OpenTofu, EC2 + Docker Compose + **RDS**, S3, SSM |
| Observabilidade | OpenTelemetry (OTLP). Local: Grafana LGTM. Prod: coletor → CloudWatch/X-Ray |

Proibido sem aprovação explícita: Redis, Kafka/SQS, novos serviços, trocar de stack, adicionar dependência.

## 3. Arquitetura

**Monolito modular**: um artefato, perfis `api` e `worker` (no piloto, `all`). Cada módulo é uma fatia vertical com **Clean Architecture**:

```
com.endoacademy.clinic.<module>/
  api/          fachada pública + eventos (ÚNICO acesso de outros módulos)
  domain/       entidades, value objects, regras (sem Spring, sem JPA)
  application/  casos de uso + ports (in/out)
  adapter/in/   web (controllers), listeners de evento
  adapter/out/  persistência, integrações externas
  config/
```

Regras (verificadas por ArchUnit + `Modulith.verify()` no CI; build falha se violar):
1. `domain` ← `application` ← `adapter`. O domínio não importa Spring, JPA, Jackson nem AWS.
2. Entidade de domínio ≠ entidade JPA; mapeamento explícito.
3. Um módulo só usa outro pela `api`. **Nada de join ou FK entre schemas.**
4. Cada módulo tem um **schema** Postgres e uma pasta de migrações Flyway próprios.
5. Tudo externo fica atrás de um **port**: `IdentityProvider`, `FileStorage`, `Clock`, `LlmClient`, `EndoAiClient`, `EmailSender`. Cada port tem adaptador real e adaptador local/falso.
6. Módulos CRUD podem ter domínio fino, mas a **estrutura é a mesma** em todos.

Módulos (nome de uma palavra; o nome do módulo é o nome do pacote em `com.endoacademy.clinic.<module>`, a pasta da spec em `specs/<module>/` e o schema do Postgres):

| Módulo | Responsabilidade |
|---|---|
| `platform` | Identidade, clínicas (tenants), usuários, perfis, branding, auditoria e telas do backoffice (`/admin`) |
| `registry` | Cadastros base: profissionais, pacientes, procedimentos e consentimentos (tipos, versões, aceites) |
| `scheduling` | Agenda do médico por clínica |
| `inventory` | Estoque: itens, movimentações, ponto de reposição, alertas, consumo por procedimento e entrega ao paciente com termo de recebimento (a assinatura dá a baixa) |
| `finance` | Financeiro |
| `reports` | Relatórios diários |
| `dashboard` | Painel do empresário (projeções de leitura) |
| `notifications` | E-mail e WhatsApp (depois SMS e push) |
| `endoai` | Integração com o Endo Academy AI (camada anticorrupção) |
| `patient360` | Histórico e visão do paciente |
| `aigateway` | Acesso único a LLMs (anonimização, custo, log de uso) |

Depois: `simulator` e `assistant`. O **backoffice não é módulo**: é um canal (interface `/admin`) que vive em `platform.adapter.in.web`. **Não criar módulo `core`, `common` ou `shared`**: código sem lugar vai para o módulo dono do conceito, e a arquitetura é revista se não houver dono. Dependências permitidas entre módulos: `docs/architecture.md`.

## 4. Multi-tenancy, segurança e LGPD

- Toda tabela de negócio tem `tenant_id` e **RLS** ativo; o tenant é definido por transação (`SET LOCAL app.tenant_id`).
- **Toda entidade nova exige teste de isolamento entre tenants.**
- Autorização no banco da aplicação (permissões fixas no código, perfis por clínica; modelos iniciais: `PLATFORM_ADMIN`, `CLINIC_ADMIN`, `DOCTOR`, `NURSE`, `MANAGER`, `STAFF`), indexada pelo `sub` do token.
- **Dado de paciente nunca vai para log, trace, métrica ou prompt de LLM** sem passar pelo `aigateway` (anonimização).
- Auditar leitura e escrita de dado de paciente (quem, quando, o quê).
- **Baixa de estoque de item entregue ao paciente só acontece com o termo de recebimento assinado**, na mesma transação da assinatura (`inventory`). A venda (`finance`) apenas **reserva**. Termo assinado é imutável; só perfil com permissão específica cancela, com justificativa, auditado. Regra completa em `docs/architecture.md`.
- **Auditoria de todas as ações relevantes**: data e hora, usuário, ação, entidade e resultado, via `AuditTrail` do `platform`. Trilha append-only; sem dado de paciente no texto da auditoria.
- Mensagens a paciente por e-mail, SMS ou WhatsApp levam o mínimo de dado pessoal e dependem de consentimento; o provedor é decisão a aprovar (seção 10).
- Segredos em SSM Parameter Store; nunca no repositório, na imagem ou em `.env` versionado.
- Branding por clínica (logo, cores) via `tenant_branding`, aplicado por CSS variables.

## 5. Spec-Driven Development (obrigatório)

Nenhuma funcionalidade começa sem spec aprovada. Fluxo em `specs/<modulo>/<feature>/`:

1. `spec.md`: problema, critérios de aceite (Given/When/Then), fora de escopo. **Humano aprova.**
2. `plan.md`: design técnico (casos de uso, ports, modelo de dados, impactos). **Humano aprova.**
3. `tasks.md`: tarefas pequenas e testáveis.
4. Implementação **test-first**. O contrato OpenAPI é atualizado junto.
5. PR fecha com spec, testes e `./mvnw verify` verdes. O spec é atualizado se a realidade divergiu.

Templates em `specs/_templates/`. Se o código precisar divergir do spec, **pare e atualize o spec antes**.

**Diário de progresso:** `docs/progress-log.md` registra onde paramos e o próximo passo (entrada mais recente no topo). Atualize ao fim de cada tarefa.

**Board de tarefas no Notion:** o board "Sistema da Clínica · Tarefas" (área privada "Projeto Endo Academy"; IDs em `docs/notion-board.md`) espelha o `tasks.md`. **A fonte da verdade é o `tasks.md`**; em conflito, ele vence. Ao trabalhar:
1. Ao **começar** uma tarefa: mover o card para `DOING` e marcar a tarefa no `tasks.md`.
2. Ao **pedir revisão** (Plannotator): `REVIEW`.
3. Se **travar** (aprovação pendente, dependência, decisão): `BLOCKED`, com o motivo em "Motivo do bloqueio".
4. Ao **concluir** (testes verdes, critério "pronto quando" cumprido): `DONE`, `[x]` no `tasks.md` e entrada no `docs/progress-log.md`.
5. Tarefa nova ou removida no `tasks.md` entra ou sai do board na mesma hora, com ID, ordem, etapa e critérios iguais.

Mover card não é mudança de escopo. O board não leva dado de paciente, segredo nem informação comercial sensível.

**Revisão obrigatória via Plannotator.** Toda spec, plano, ADR e documento de arquitetura só é considerado aprovado depois de revisado no [Plannotator](https://github.com/backnotprop/plannotator):
1. A IA escreve o documento com status `Rascunho`.
2. A IA abre a revisão (`plannotator annotate <arquivo ou pasta> --gate --json --result-file <arquivo>`, com Approve ativo) e **para**, aguardando o feedback. Resultado `approved` ou `annotated` (com comentários); `dismissed` (aba fechada) **não** é aprovação.
3. O humano comenta e aprova no navegador. A IA aplica os comentários e reabre a revisão se houver mudança relevante.
4. **Só com aprovação explícita do humano** o status passa a `Aprovada` e a caixa de aprovação do documento é preenchida. A IA nunca se auto-aprova.
5. Privacidade: manter `PLANNOTATOR_SHARE=disabled` e não usar compartilhamento por link nem "Ask AI" com documentos do cliente sem decisão explícita.

## 6. Testes

- **Unitários** (`*Test`, Surefire): `domain` e `application`, com fakes dos ports. Sem Spring, sem banco. Meta de cobertura de linhas e branches em `domain` + `application`: **≥ 85%**, com portão JaCoCo no CI.
- **Integração** (`*IT`, Failsafe): Testcontainers (Postgres e LocalStack), Flyway real, API completa, **isolamento RLS**, `@ApplicationModuleTest`.
- **Arquitetura**: ArchUnit + Modulith.
- Front: Vitest + Testing Library; Playwright para fluxos críticos.
- Bug corrigido ⇒ teste que reproduz o bug antes.
- Comando de verdade: `./mvnw verify` (unitários + integração + cobertura + arquitetura).

## 7. Dependências e segurança da cadeia

- Versões vêm do **BOM do Spring Boot**; sobrescrever só para corrigir CVE, com comentário e link.
- Somente releases GA estáveis (nada de milestone, RC, snapshot).
- Versões fixas, atualizadas continuamente (Renovate/Dependabot; patch com CI verde pode ser automático).
- CI **falha** com vulnerabilidade Alta ou Crítica (OWASP dependency-check/OSV + Trivy nas imagens que vão a produção; imagens só de desenvolvimento e teste ficam fora do bloqueio, ADR-0005). Exceções só no arquivo de supressão, com justificativa e **data de expiração**.
- Prazos de correção: crítica ≤ 48 h, alta ≤ 7 dias.
- SBOM CycloneDX por build; imagem base com digest fixado, rebuild semanal.
- `maven-enforcer-plugin`: JDK 25, convergência de dependências.
- App e backoffice: lockfile versionado, `npm audit` no CI, Node LTS.

## 8. Paridade local e produção

- Mesma imagem em todos os ambientes; só variáveis de ambiente mudam.
- `docker compose -f docker/compose.yaml up -d` sobe Postgres, LocalStack, OIDC mock e Grafana LGTM.
- A aplicação só fala **OTLP** (`OTEL_EXPORTER_OTLP_ENDPOINT`); o destino é configuração do coletor.
- Antes de qualquer go-live: restauração de snapshot do RDS testada, ambiente de fumaça efêmero na AWS (apply, testes, destroy) e `plan` revisado.

## 9. Convenções

### Git, commits, push e revisão de código

- **Repositório `clinic`** (GitHub, conta `deniscaguilar`, **público por enquanto**, porque a conta é Free; o nome é provisório, como o do produto). Criar outro repositório ou conectar outro remoto são ações que a IA só faz com confirmação explícita (seção 10).
- **Repositório público:** tudo o que for commitado fica visível a qualquer pessoa. Nunca commitar segredo, chave, `.env`, dado de paciente, exame real ou informação de negócio sensível (estratégia comercial, preços, contratos); o que for assim fica fora do repositório (Notion ou arquivo local ignorado pelo git). **Secret scanning e push protection** do GitHub ficam ativos (estão desde a criação do repositório). Nomes de pessoas e de clientes reais não entram no repositório: usar nomes fictícios (ex.: "Clínica da DCA"). Revisar a visibilidade quando o plano permitir privado com as proteções de branch necessárias.
- Trunk-based: `main` sempre estável; branches curtas; **PR até para trabalho solo**; o CI é o portão. Releases com tag semântica (`v0.1.0`).
- Commits convencionais (`feat:`, `fix:`, `chore:`…), uma mudança lógica por commit.
- **Uma única revisão por mudança, antes do commit (regra do Dênis, 29/10/2026).** A IA abre `plannotator review` do diff local, **com Approve ativo, e para**. A **aprovação** dessa revisão autoriza tudo o que segue, **sem nova pergunta e sem segunda revisão**: commit, `git push`, abertura do PR, **merge (squash) na `main`**, **remoção da branch** (remota e local) e atualização da `main` local. A IA não pergunta "posso mergear?" nem "posso remover as branches?". Comentários são aplicados e a revisão é reaberta se a mudança for relevante. Se depois da aprovação o conteúdo mudar (novo commit, conflito de rebase), a revisão é refeita. O PR não é revisado pela URL: o GitHub não deixa o autor aprovar o próprio PR, e o desenvolvimento é solo; aprovação no GitHub **não** é exigida e a proteção da `main` exige só o CI verde.
- **Limites que continuam valendo:** a IA **nunca faz force push**, nunca faz commit nem push direto na `main` (só por PR) e **só segue com o que passou pela revisão aprovada no Plannotator** (sem aprovação, não faz commit, push, PR nem merge). Quando houver CI, o merge só acontece com o CI verde. Mudança que caia na lista da seção 10 continua exigindo a decisão específica do Dênis sobre o assunto (não sobre o merge). Ao final, a IA relata o que fez (commit, PR, merge).
- **Ao iniciar o trabalho, depois de ler este arquivo:** a IA **atualiza a `main`** (`git checkout main` e `git pull`) e confere `git status`, para começar com o projeto atualizado. Se houver alteração local não commitada, **não descarta**: avisa o Dênis.
- Revisão de documento (spec, plano, ADR) segue o fluxo da seção 5; revisão de código segue este fluxo. As duas mantêm `PLANNOTATOR_SHARE=disabled`.

### Código e dados

- Dinheiro em `BigDecimal` ou centavos (`Long`); fuso `America/Sao_Paulo` explícito; nunca `LocalDateTime` sem fuso para instantes.
- **Migração Flyway já aplicada é imutável**; correção vem em nova migração.
- Decisões novas de arquitetura viram **ADR** em `docs/adr/`.
- **Idioma (regra firme):**
  - **Português:** specs, ADRs, roadmap, README e demais documentação, **mensagens de commit** (a descrição; o prefixo do tipo fica em inglês, ex.: `feat: cria cadastro de clínicas`) e **nomes de branch** (sem acentos, ex.: `feat/m0-tenancy-por-clinica`), para que qualquer desenvolvedor entenda o histórico.
  - **Inglês:** todo o código e tudo que é estrutura: **pastas e nomes de arquivos do projeto**, pacotes, classes, funções, variáveis, testes e seus nomes, comentários, tabelas e colunas (`snake_case`), migrações Flyway, endpoints e OpenAPI, enums, logs, códigos de erro, módulos Maven e recursos de infra e containers.
  - O que o usuário vê (textos da UI e mensagens de erro exibidas) é **pt-BR via i18n**, com chaves em inglês.
- Termos de domínio seguem o glossário da seção 12; uma spec escrita em português usa o termo em português, e o código usa o equivalente em inglês.

## 10. A IA deve parar e perguntar antes de

- Alterar migração já aplicada, configuração de segurança, RLS ou autenticação.
- Adicionar ou trocar dependência, imagem base, provider de infra ou serviço AWS.
- Fazer deploy, `apply` de infra ou qualquer ação que gere custo.
- Criar o repositório, conectar o remoto, fazer commit, push ou merge sem a revisão aprovada no Plannotator (com a revisão única aprovada, a IA segue direto até o merge, sem nova pergunta; ver seção 9).
- Enviar dado a serviço externo (LLM, e-mail, push, WhatsApp, SMS).
- Criar ou alterar uma fila, ou colocar dado de paciente numa mensagem.
- Violar uma regra deste arquivo ("só desta vez").

## 11. Definição de pronto

Spec aprovada e atualizada • testes unitários e de integração verdes • cobertura ≥ meta • ArchUnit/Modulith verdes • scan sem Alta/Crítica • teste de isolamento de tenant (se houver dado novo) • OpenAPI atualizado • sem dado de paciente em logs • ADR criada se houve decisão de arquitetura • nenhum identificador em português no código.

## 12. Glossário de domínio (português → inglês)

Use sempre o termo em inglês no código. Termos novos entram aqui antes de serem usados.

| Português | Código (inglês) |
|---|---|
| clínica | `clinic` (tenant) |
| médico | `doctor` |
| paciente | `patient` |
| profissional | `practitioner` |
| procedimento | `procedure` |
| consulta | `appointment` (agendamento) / `consultation` (atendimento realizado) |
| anamnese | `anamnesis` |
| prontuário | `medicalRecord` |
| histórico do paciente | `patientHistory` |
| estoque | `inventory` |
| item de estoque | `inventoryItem` |
| baixa de estoque | `stockWriteOff` |
| entrega ao paciente | `dispensation` |
| termo de recebimento | `receiptTerm` |
| reserva de estoque | `reservation` |
| lote | `batch` |
| descarte | `disposal` |
| agenda | `schedule` (módulo `scheduling`) |
| consentimento | `consent` |
| tipo de consentimento | `consentType` |
| validade | `expiryDate` |
| venda de procedimento | `procedureSale` |
| auditoria | `auditTrail` |
| financeiro | `finance` |
| faturamento | `revenue` |
| meta | `goal` |
| relatório diário | `dailyReport` |
| mentorado | `mentee` |
| gestor | `manager` |
| equipe / colaborador | `staff` |
| identidade visual | `branding` |
| clínica ativa/inativa | `ACTIVE` / `INACTIVE` (status) |
