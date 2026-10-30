# Plano: M0 · Fundação (identidade, tenancy, branding e backoffice)

- **Spec:** `spec.md` (status: Aprovada)
- **Status:** Aprovada
- **Módulos:** `platform`, `registry`, `notifications` (mais o app React em `app/`)

## 1. Visão da solução

O M0 entrega o esqueleto que todos os módulos seguintes vão usar: autenticação OIDC, a clínica (tenant) como fronteira de isolamento imposta pelo banco, permissões e perfis, auditoria append-only, cadastros base (profissional, paciente, procedimento, consentimento) e a casca do PWA com o backoffice em `/admin`.

Três ideias sustentam o desenho:

1. **O banco é a última barreira.** A aplicação conecta com um papel sem privilégio de dono (`clinic_app`), todas as tabelas de negócio têm RLS **forçada**, e o tenant chega por transação (`set_config('app.tenant_id', ..., true)`). Sem contexto de tenant, a consulta devolve zero linhas (falha fechada). Um teste de arquitetura de banco varre o catálogo e reprova qualquer tabela com `tenant_id` sem RLS (garante CA10 para entidades futuras).
2. **Autorização no banco da aplicação, token só identifica.** O token traz apenas o `sub`. A clínica ativa vem no cabeçalho `X-Clinic-Id`, validada contra os vínculos do usuário a cada requisição (sem sessão no servidor). Permissões são fixas no código; perfis por clínica são conjuntos delas.
3. **Módulos conversam por `api` e eventos com ids, nunca com dado de paciente.** `registry` publica `ConsentAccepted` e `ConsentRevoked`; `notifications` reage, grava a intenção de envio numa tabela e um worker envia com retentativa.

**Fatias de entrega sugeridas** (cada uma termina com `./mvnw verify` verde; viram grupos no `tasks.md`):

| Fatia | Conteúdo | RF / CA |
|---|---|---|
| S1 | Esqueleto Maven, perfis, banco, Flyway por módulo, papéis, contexto de tenant, RLS, JWT, auditoria, observabilidade, ArchUnit/Modulith | RF1, RF8, RF9; CA3, CA8, CA9, CA10 |
| S2 | Clínicas, usuários, vínculos, perfis e permissões, convite via `IdentityProvider` | RF2, RF3, RF4, RF4a; CA4, CA5, CA11, CA12 |
| S3 | Branding e armazenamento de arquivos | RF7; CA6, CA7 |
| S4 | `registry`: profissional (com horário), procedimento, paciente | RF4, RF5, RF6; CA1, CA2, CA13, CA14 |
| S5 | Consentimento (tipos, versões, aceite, revogação) e `notifications` | RF5a, RF11 a RF13; CA15 a CA18 |
| S6 | App React: shell PWA, login, escolha de clínica, branding, telas `/admin` e de cadastros | RF10; CA6 (lado front) |

## 2. Casos de uso (camada application)

Todo caso de uso recebe o `CurrentUser` (usuário, clínica ativa, permissões) e exige a permissão indicada antes de agir; sem ela, 403 e auditoria com resultado `DENIED`.

### `platform`

| Caso de uso | Entrada | Saída | Regras |
|---|---|---|---|
| `ResolveSession` | `sub`, `X-Clinic-Id` | usuário, perfis, permissões, branding | Vínculo ativo e clínica `ACTIVE`, senão 403. Audita `LOGIN`. |
| `ListMyClinics` | `sub` | clínicas do usuário | Não exige clínica ativa (é a tela de escolha). |
| `CreateClinic` (`PLATFORM_ADMIN`) | nome, dados básicos, e-mail do 1º admin | clínica | Ver fluxo de convite abaixo. Cria os perfis-modelo e o branding padrão. |
| `UpdateClinic` / `DeactivateClinic` / `ActivateClinic` | id, campos | clínica | Clínica `INACTIVE` bloqueia todos os vínculos; dados são preservados. |
| `InviteUser` (`MANAGE_USERS`) | e-mail, nome, perfis | vínculo | Se o e-mail já tem identidade (outra clínica), só cria o vínculo, **mesmo login**. A resposta não revela se o e-mail já existia. |
| `UpdateUserRoles` / `DeactivateMembership` | vínculo, perfis | vínculo | Não permite remover o último `CLINIC_ADMIN` ativo da clínica. |
| `ResendInvitation` | vínculo | - | Só para vínculos `PENDING`. |
| `CreateRole` / `UpdateRole` / `DeleteRole` (`MANAGE_ROLES`) | nome, permissões | perfil | Só aceita permissões do catálogo; perfil em uso não pode ser apagado. Permissão exclusiva de plataforma não é atribuível. |
| `UpdateBranding` (`MANAGE_BRANDING`) | nome, cores | branding | Valida contraste (ver seção 3). |
| `UploadBrandAsset` | logo ou favicon | URL assinada | Tipo e tamanho validados; vai pelo port `FileStorage`. |
| `QueryAuditTrail` (`VIEW_AUDIT_TRAIL`) | filtros, página | eventos | Só da clínica ativa (RLS). |
| `RecordAudit` (`api`) | ação, entidade, resultado | - | Usado por todos os módulos (port `AuditTrail`). |

**Fluxo de convite (evita chamada externa dentro da transação).**
1. Transação 1: cria clínica (ou vínculo) com vínculo `PENDING`, perfis e `audit_event`.
2. Fora da transação: `IdentityProvider.createUser(email, name)` devolve o `sub`. Falha aqui deixa o vínculo `PENDING` e visível para `ResendInvitation`.
3. Transação 2: grava o `sub` no `app_user`, vínculo passa a `ACTIVE`.
No Cognito, o próprio `AdminCreateUser` envia o e-mail de convite.

### `registry`

| Caso de uso | Entrada | Saída | Regras |
|---|---|---|---|
| `RegisterPractitioner` / `UpdatePractitioner` (`MANAGE_PRACTITIONERS`) | nome, tipo (`DOCTOR`, `NURSE`, `OTHER`), registro profissional (opcional), e-mail, horário de trabalho | profissional | Cria/vincula o usuário via `platform.api`. Médico em duas clínicas = um profissional **por clínica**, cada um com seu horário. Horário: dia da semana, início, fim, sem sobreposição. |
| `CreateProcedure` / `UpdateProcedure` (`MANAGE_PROCEDURES`) | nome, descrição, ativo | procedimento | Nome único por clínica. |
| `RegisterPatient` (`MANAGE_PATIENTS`) | dados do RF5 + aceites dos tipos obrigatórios | paciente | Valida tudo antes de gravar; CPF pelos dígitos verificadores e único por clínica; ao menos WhatsApp ou e-mail; aceite de **todos** os tipos obrigatórios, senão nada é gravado (CA13, CA14). Paciente e aceites na mesma transação. |
| `UpdatePatient` | id, campos | paciente | Mesmas validações. CPF só muda com permissão e audita. |
| `GetPatient` / `ListPatients` / `SearchPatientByCpf` (`VIEW_PATIENTS`) | id, filtros, página | paciente(s) | Auditam leitura. Busca por CPF vai em `POST` (CPF nunca em URL). |
| `CreateConsentType` / `ReviseConsentType` (`MANAGE_CONSENT_TYPES`) | nome, texto, obrigatório no cadastro, controla mensagens (sim/não) | tipo + versão | Revisar texto cria **nova versão** (imutável); nunca edita a anterior (CA15). Apenas um tipo da clínica pode ter `controls_messaging`. |
| `ImportConsentTemplate` | id do modelo | tipo | Copia o modelo oferecido pelo `PLATFORM_ADMIN` para a clínica. |
| `AcceptConsent` (`RECORD_CONSENT`) | paciente, tipo | aceite | Liga à **versão vigente**; registra instante (via `Clock`), canal `IN_CLINIC` e usuário. Publica `ConsentAccepted`. |
| `RevokeConsent` (`RECORD_CONSENT`) | aceite | aceite | Registra revogação (quem, quando). Publica `ConsentRevoked`. |
| `HasActiveMessagingConsent` (`api`) | paciente | boolean | Consulta usada por `notifications`. |
| `GetPatientContact` (`api`) | paciente | WhatsApp, e-mail, nome | Uso interno de `notifications`; devolve só o necessário. |

### `notifications`

| Caso de uso | Entrada | Saída | Regras |
|---|---|---|---|
| `OnConsentAccepted` (listener) | evento (ids) | linhas em `notification` | Uma linha por canal cadastrado (e-mail, WhatsApp). Texto com o mínimo: nome do tipo e data (CA16). |
| `OnConsentRevoked` (listener) | evento (ids) | - | Pendentes do paciente passam a `CANCELLED`. |
| `DispatchPending` (worker) | - | - | Pega lote com `FOR UPDATE SKIP LOCKED`; antes de enviar, checa consentimento de mensagens; sem ele, marca `BLOCKED_NO_CONSENT` e não envia (CA17). Falha: backoff exponencial, máximo de tentativas, depois `FAILED` (CA18). |
| `ListNotifications` (`VIEW_NOTIFICATIONS`) | filtros | status | Mostra canal, status e tentativas, **sem** conteúdo nem destinatário. |

## 3. Domínio

### `platform.domain`
- **`Clinic`** (`ACTIVE`/`INACTIVE`), **`AppUser`** (`sub`, e-mail, nome), **`Membership`** (usuário × clínica, status `PENDING`/`ACTIVE`/`INACTIVE`, perfis), **`Role`** (clínica, nome, conjunto de permissões), **`PlatformAdmin`**.
- **`Permission`**: código textual (`MANAGE_USERS`). O catálogo é montado no startup por beans `PermissionContributor` (interface em `platform.api`). Cada módulo declara as suas permissões e as concessões padrão por perfil-modelo; o `platform` não conhece `registry` em tempo de compilação.
- **`Branding`** e **`HexColor`**; **`ContrastPolicy`**: razão de contraste WCAG 2.x ≥ **4,5** entre a cor primária e o melhor texto (branco ou preto, o que der mais contraste). O front usa o mesmo texto calculado (`onPrimary`).
- **`AuditEvent`**: imutável, só ids e nomes de campos.

### `registry.domain`
- **`Cpf`** (value object, dígitos verificadores, rejeita sequências repetidas), **`Email`**, **`Phone`**, **`Address`** (CEP, UF válida).
- **`Patient`**: invariantes do RF5 (nome completo, CPF, nascimento no passado, endereço, ao menos um contato).
- **`Practitioner`** e **`WorkSchedule`** (intervalos por dia da semana, sem sobreposição).
- **`Procedure`**.
- **`ConsentType`** com lista de **`ConsentVersion`** (número crescente, texto imutável); **`ConsentGrant`** (aceite ligado a uma versão; revogação preenche `revokedAt`/`revokedBy`, não apaga).

### Eventos (em `api` do dono, só ids e instante)
`ClinicCreated(clinicId)`, `ConsentAccepted(tenantId, patientId, grantId)`, `ConsentRevoked(tenantId, patientId, grantId)`. Eventos são persistidos pelo registro do Spring Modulith (JDBC), que faz o papel de outbox.

### `notifications.domain`
**`Notification`** (canal, status `PENDING`/`SENT`/`FAILED`/`BLOCKED_NO_CONSENT`/`CANCELLED`, tentativas, próxima tentativa) e a política de retentativa.

## 4. Ports

| Port | Direção | Adaptador real | Adaptador local/falso |
|---|---|---|---|
| `IdentityProvider` (`platform`) | out | Cognito (`AdminCreateUser`, `AdminDisableUser`) | Falso em memória: `sub` determinístico a partir do e-mail, alinhado ao `oidc-config.json` do mock |
| `FileStorage` (`platform.api`) | out | S3 (SSE, URL assinada de leitura, curta) | S3 no LocalStack |
| `AuditTrail` (`platform.api`) | in (para outros módulos) | JDBC no schema `platform` | O mesmo (banco real nos testes de integração); fake em memória nos unitários |
| `Clock` | out | `java.time.Clock` do sistema, fuso explícito | `Clock.fixed` nos testes |
| `TenantContext` / `CurrentUser` (`platform.api`) | in | Preenchidos pelo filtro de autenticação | Fixados pelos testes |
| `PermissionContributor` (`platform.api`) | in | Um bean por módulo | Fake nos testes |
| `EmailSender` (`notifications`) | out | SES | LocalStack SES (grava mensagens) |
| `WhatsAppSender` (`notifications`) | out | **Nenhum no M0** (provedor a definir) | Falso que registra o envio em memória e em log sem destinatário |
| `PatientContactLookup`, `MessagingConsentLookup` (`notifications`) | out | Chamam a `api` do `registry` | Fakes |

Nenhum adaptador real do M0 é executado contra a AWS durante o desenvolvimento. A verificação do Cognito acontece no ambiente de fumaça antes do go-live (ver riscos).

## 5. Persistência

**Papéis do banco (criados pelo ambiente, não pelas migrações).** `clinic_migrator` (dono dos schemas, roda Flyway) e `clinic_app` (usada pela aplicação: sem `BYPASSRLS`, sem dono de tabela). Local: script de init no Compose. Produção: provisionamento do RDS. As migrações só fazem `GRANT`. Duas `DataSource`s: migração e aplicação.

**Schemas e migrações.** Um schema por módulo (`platform`, `registry`, `notifications`) mais `eventing` para o registro de eventos do Modulith. Pasta de migrações por módulo (`db/migration/<module>/`), uma instância do Flyway por módulo, histórico no próprio schema, executadas nessa ordem: `platform`, `registry`, `notifications`, `eventing`.

**Variáveis de sessão do banco** (definidas com `set_config(..., true)`, valem só na transação):
- `app.tenant_id`: clínica ativa.
- `app.user_sub`: usado apenas para resolver os vínculos do próprio usuário antes de existir clínica ativa.
- `app.scope`: `platform` somente dentro de casos de uso de `PLATFORM_ADMIN` (clínicas, convite do 1º admin, modelos de consentimento). **Nunca** libera tabelas de paciente.

**Política padrão** das tabelas de negócio (`ENABLE` e `FORCE ROW LEVEL SECURITY`):
`USING (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid) WITH CHECK (idem)`. Sem variável, nenhuma linha.

### `platform`
| Tabela | `tenant_id` | Observações |
|---|---|---|
| `clinic` | `id` é o tenant | RLS: `id = tenant` ou `scope = platform` |
| `app_user` | não | `sub` único, e-mail único. Leitura pelo próprio `sub` (`app.user_sub`) ou escopo plataforma; sem dado de paciente |
| `platform_admin` | não | `user_id` |
| `membership` | sim | usuário × clínica, status; RLS: tenant, ou `user_sub` do próprio usuário (só `SELECT`), ou escopo plataforma |
| `role`, `role_permission`, `membership_role` | sim | perfil e permissões por clínica |
| `tenant_branding` | sim | nome, cores, chaves S3 de logo e favicon, `on_primary` |
| `audit_event` | sim (nulo para ação sem clínica) | `id`, `occurred_at`, `user_id`, `action`, `entity_type`, `entity_id`, `outcome`, `channel`, `ip`, `details` (jsonb, só nomes de campos). **`clinic_app` tem apenas `INSERT` e `SELECT`**; `UPDATE`, `DELETE` e `TRUNCATE` revogados, com gatilho que rejeita também para outros papéis não donos |

### `registry`
| Tabela | Observações |
|---|---|
| `practitioner` | `user_id` (id lógico, sem FK entre schemas), tipo, registro profissional, ativo |
| `practitioner_work_schedule` | profissional, dia da semana, início, fim (`CHECK` de intervalo) |
| `procedure` | nome único por clínica, ativo |
| `patient` | nome, CPF, nascimento, WhatsApp, e-mail, telefone fixo, endereço; **`UNIQUE (tenant_id, cpf)`**; índices por tenant e nome |
| `consent_type` | clínica, nome, `required_on_signup`, `controls_messaging` (índice único parcial: um por clínica), ativo |
| `consent_type_version` | tipo, número, texto, criado em; imutável (gatilho) |
| `consent_grant` | paciente, versão, `accepted_at`, canal, `recorded_by`, `revoked_at`, `revoked_by` |
| `consent_template` | sem tenant; leitura para todos, escrita só no escopo plataforma |

### `notifications`
| Tabela | Observações |
|---|---|
| `notification` | tenant, `patient_id`, canal, tipo (modelo), status, tentativas, `next_attempt_at`, `last_error_code`. **Sem destinatário nem texto**: o worker resolve o contato e monta a mensagem no envio, via `api` do `registry` |

### Convenções de dados
IDs UUID gerados pela aplicação. Instantes em `timestamptz` (UTC). Nomes de tabela e coluna em inglês, `snake_case`. CPF e demais dados pessoais dependem de criptografia em repouso do RDS (e do volume local sem dado real). Sem criptografia por coluna no M0 (ver decisões a validar).

## 6. API

Prefixo `/api/v1`. JSON. Erros em Problem Details (RFC 9457) com campo `code` estável em inglês (`CPF_INVALID`, `CPF_ALREADY_REGISTERED`, `CONSENT_REQUIRED`, `BRAND_CONTRAST_INSUFFICIENT`, ...) e **sem eco do valor rejeitado**. A mensagem exibida ao usuário vem do i18n do front (pt-BR). Listagens com `page`/`size` (padrão 20, máximo 100) e ordenação fixa. Recurso de outra clínica responde **404**. O contrato é gerado (springdoc), versionado em `backend/openapi/openapi.yaml` e comparado no CI; o cliente TypeScript do front é gerado dele.

| Área | Endpoints |
|---|---|
| Sessão | `GET /me`, `GET /me/clinics`, `GET /session` (exige `X-Clinic-Id`; devolve usuário, perfis, permissões e branding) |
| Backoffice (`PLATFORM_ADMIN`) | `POST/GET /admin/clinics`, `GET/PATCH /admin/clinics/{id}`, `POST /admin/clinics/{id}/deactivate` e `/activate`, `POST /admin/clinics/{id}/admins/{membershipId}/resend-invitation`, `CRUD /admin/consent-templates` |
| Clínica: usuários e perfis | `GET/POST /users`, `PATCH /users/{id}`, `POST /users/{id}/deactivate`, `POST /users/{id}/resend-invitation`, `CRUD /roles`, `GET /permissions` |
| Clínica: branding | `GET/PUT /branding`, `PUT /branding/logo`, `PUT /branding/favicon` (multipart; PNG, JPEG ou WebP; **sem SVG**; limite de tamanho) |
| Auditoria | `GET /audit-events` (filtros por período, usuário, ação, entidade) |
| Registry | `CRUD /practitioners`, `CRUD /procedures`, `POST/GET /patients`, `GET/PUT /patients/{id}`, `POST /patients/search` (por CPF), `CRUD /consent-types` (`PUT` cria nova versão), `POST /consent-types/import-template`, `GET/POST /patients/{id}/consents`, `POST /patients/{id}/consents/{grantId}/revoke` |
| Notificações | `GET /notifications` |

Todas as rotas, exceto as de saúde, exigem token válido (CA3). `X-Clinic-Id` é obrigatório fora de `/me`, `/me/clinics` e `/admin/**`.

**Front (`app/`).** React + TypeScript, Vite, PWA instalável (service worker apenas para o shell, **sem cache de resposta da API** por conter dado de paciente), OIDC Authorization Code + PKCE, cliente gerado do OpenAPI, i18n com chaves em inglês e textos pt-BR, navegação por permissões da sessão, branding por CSS variables (`--color-primary`, `--color-on-primary`, ...). Telas do M0: login e escolha de clínica; `/admin` (clínicas, modelos de consentimento); clínica (usuários, perfis, branding, auditoria, profissionais, procedimentos, pacientes, tipos de consentimento, aceite e revogação). Testes com Vitest e Testing Library; um fluxo crítico em Playwright (login, escolha de clínica, cadastro de paciente com consentimento).

## 7. Segurança, LGPD e observabilidade

- **Autenticação:** resource server OIDC genérico (issuer e JWKS por configuração). Valida assinatura, `iss`, `exp` e `aud`; só `sub` é usado como identidade. Token inválido ou ausente: 401.
- **Autorização:** `Authorizer` na camada `application` checa a permissão do caso de uso; nada de regra de acesso espalhada em controller. Perfis-modelo e concessões padrão vêm dos `PermissionContributor` (proposta inicial: `CLINIC_ADMIN` todas; `DOCTOR` e `NURSE` `VIEW_PATIENTS`; `MANAGER` `VIEW_PATIENTS`; `STAFF` `VIEW_PATIENTS`, `MANAGE_PATIENTS`, `RECORD_CONSENT`).
- **Contexto de tenant:** `TenantAwareTransactionManager` (estende `JpaTransactionManager`) executa os `set_config` no início de cada transação. Listeners e workers rodam sob `TenantScope.runAs(tenantId)`, com o tenant vindo do evento. Sem contexto, falha fechada.
- **Auditoria:** gravada **na mesma transação** da ação; para dado de paciente, falha de auditoria desfaz a ação. Negativas (`DENIED`, `FAILURE`) são gravadas em transação separada (`REQUIRES_NEW`), pois a principal foi revertida. Listagem de pacientes gera um evento com a contagem, leitura individual gera um por paciente. Detalhes guardam só nomes de campos.
- **Sem dado de paciente em log, trace ou métrica:** CPF e demais dados nunca vão em URL ou query (por isso a busca por CPF é `POST`), corpo de requisição não é logado, mensagens de erro não repetem valores, atributos de span limitados a `tenant_id`, `user_id`, rota e status. Teste automatizado procura CPF e e-mail de teste na saída de log e nos spans exportados.
- **OTLP desde o primeiro endpoint:** métricas e traces via Micrometer/OpenTelemetry; um filtro depois da autenticação coloca `tenant_id` e `user_id` no contexto de trace.
- **Branding:** upload valida tipo pelo conteúdo (não pela extensão), tamanho e dimensões; sem SVG; arquivos servidos por URL assinada curta.
- **Notificações:** conteúdo mínimo (nome do tipo e data), nunca texto de consentimento; sem consentimento de mensagens, nada sai e a tentativa fica registrada.
- **Cabeçalhos e CORS:** CORS restrito à origem do app; cabeçalhos de segurança padrão; sem cookie de sessão.
- **Segredos:** senhas dos papéis do banco e demais segredos por variável de ambiente (SSM em produção). Nada no repositório.

## 8. Estratégia de testes

- **Unitários (`*Test`, sem Spring e sem banco):** `Cpf`, `Address`, `Patient`, `WorkSchedule` (sobreposição), versionamento de `ConsentType`, `ContrastPolicy` (casos de borda do 4,5), catálogo de permissões, política de retentativa, casos de uso com fakes dos ports. Meta ≥ 85% de linhas e branches em `domain` + `application`, com portão JaCoCo.
- **Integração (`*IT`, Testcontainers Postgres e LocalStack):**
  - Flyway real, nas duas contas (`clinic_migrator` e `clinic_app`).
  - API completa com `jwt()` do Spring Security Test; dois ITs sobem o `mock-oauth2-server` para validar o fluxo OIDC de verdade (CA3).
  - Auditoria: `UPDATE`/`DELETE` em `audit_event` com a conexão da aplicação falha (CA8).
  - Fluxo de consentimento ponta a ponta com adaptadores locais: aceite, e-mail e WhatsApp enviados, falha forçada do sender mantém o aceite e reenvia, revogação bloqueia o envio (CA16 a CA18).
  - Convite: falha no `IdentityProvider` deixa vínculo `PENDING` e reenvio resolve (CA5).
  - Perfis: usuário com perfil restrito recebe 403 fora das permissões (CA12); médico em duas clínicas vê só a clínica escolhida (CA11).
  - Branding: contraste ruim rejeitado (CA7), cores distintas por clínica (CA6).
- **Isolamento de tenant (CA1, CA2, CA10):**
  - `TenantIsolationIT` genérico, por tabela com `tenant_id`: com a conexão `clinic_app`, sem contexto lê zero linhas; com o tenant A não lê nem altera linha de B; `INSERT` com `tenant_id` de B é recusado pelo `WITH CHECK`.
  - **Teste de catálogo:** consulta `information_schema`/`pg_class` e reprova se alguma tabela com `tenant_id` não tiver RLS habilitada **e forçada** e ao menos uma política. Entidade nova sem proteção quebra o build.
  - ITs de API por entidade: 404 ao acessar por ID de outra clínica.
  - Teste de que `app.scope = platform` **não** abre tabelas de paciente.
- **Arquitetura:** ArchUnit (`domain` sem Spring/JPA/Jackson/AWS; `domain` ← `application` ← `adapter`; módulos só via `api`; entidade de domínio ≠ entidade JPA) e `Modulith.verify()` com as dependências do `docs/architecture.md`. Acrescentar regra que impede `adapter` de um módulo acessar tabela de outro schema.
- **Segurança da cadeia:** OWASP dependency-check (falha em Alta/Crítica), SBOM CycloneDX, `maven-enforcer-plugin` (JDK 25, convergência), conforme ADR-0005. Trivy na imagem entra quando houver Dockerfile (fora do M0, ver "Fora do plano").
- **Front:** Vitest + Testing Library (aplicação do branding, navegação por permissão, formulários e erros); Playwright para o fluxo crítico.
- **CA9:** a suíte roda com `docker compose up` (ou só Testcontainers) sem internet além de dependências; o LocalStack é a única origem de S3 e SES.

## 9. Riscos, decisões a validar e impactos

### 9.1 Riscos e impactos

- **Contexto de tenant no `JpaTransactionManager`.** Obter a conexão no início da transação depende do dialeto JPA do Hibernate 7. Primeira tarefa da S1 é uma prova de conceito com teste de vazamento entre transações no pool (`set_config(..., true)` deve zerar ao fim). Se não funcionar, alternativa: `ConnectionProvider` do Hibernate com o mesmo teste.
- **Cognito não roda no LocalStack gratuito.** O LocalStack fica fixado na **4.14.0** (última Community sem token; ADR-0012). O adaptador do Cognito terá teste unitário com cliente simulado e só será exercitado de verdade no ambiente de fumaça da AWS, antes do go-live. Até lá, o login real fica coberto pelo `mock-oauth2-server`.
- **Compose nunca foi subido.** Imagens e configuração podem exigir ajustes na S1 (e o script de criação dos papéis do banco será acrescentado ao `docker/`).
- **Spring Modulith 2.1.x com Boot 4.1.x** ainda não foi verificado na prática; confirmar na S1 antes de montar o resto sobre ele.
- **SES** em produção exige verificação de domínio e saída do sandbox; fora do M0, mas bloqueia o envio real de e-mail.
- **WhatsApp real** não existe no M0 (decisão de provedor pendente); CA16 é provado só com o adaptador local.
- **Jurídico:** texto do consentimento e retenção continuam pendentes e precisam estar fechados antes de qualquer dado real.
- **Impactos nos outros módulos:** `inventory`, `finance` e demais passam a usar `AuditTrail`, `CurrentUser`, `TenantScope` e o catálogo de permissões por contribuidor; o `registry.api` expõe contato e consentimento do paciente para `notifications` (e depois `inventory`, para a confirmação do termo de recebimento).
- **Documentação a corrigir junto:** o ADR-0007 ainda lista os perfis sem `NURSE`; atualizar na aprovação deste plano.

### 9.2 Decisões a validar na revisão

1. **Clínica ativa por cabeçalho `X-Clinic-Id`** validado a cada requisição (sem sessão no servidor), em vez de claim no token. Recomendo o cabeçalho: não depende do Cognito e permite trocar de clínica sem novo login.
2. **Escopo `platform` na RLS** (`app.scope`) restrito a `clinic`, `app_user`, `membership`, `audit_event` e `consent_template`, para o `PLATFORM_ADMIN` operar entre clínicas. Toca RLS; vai para o **ADR-0010**.
3. **Regra de contraste:** razão ≥ 4,5 da cor primária com o melhor texto (branco ou preto). A alternativa mais rígida (sempre texto branco) rejeitaria cores claras legítimas.
4. **CPF em texto no banco**, protegido por criptografia em repouso do RDS e por RLS, em vez de criptografia por coluna (que dificultaria busca e unicidade). Revisitar quando houver parecer jurídico.
5. **Perfis-modelo e permissões iniciais** da seção 7 (em especial `STAFF` poder cadastrar paciente).
6. **Fila de notificações em tabela + worker** com `SKIP LOCKED` (sem SQS, conforme proibição de novos serviços); registrar no **ADR-0011**.
7. **Dependências novas**, para aprovação em bloco (CLAUDE.md, seção 10). Versões vêm do BOM do Spring Boot sempre que possível:
   - **Backend, runtime:** `spring-boot-starter-web`, `-validation`, `-actuator`, `-oauth2-resource-server`, `-data-jpa`, `-flyway` com `flyway-database-postgresql`, driver `postgresql`, `spring-modulith-starter-core`, `-starter-jdbc` e `-observability`, `micrometer-tracing-bridge-otel` e `opentelemetry-exporter-otlp`, `springdoc-openapi`, `jackson-module-kotlin`, `kotlin-reflect`, AWS SDK v2 (`s3`, `ses`, `cognitoidentityprovider`).
   - **Backend, teste e build:** `spring-boot-starter-test`, `spring-security-test`, `spring-modulith-starter-test`, Testcontainers (`junit-jupiter`, `postgresql`, `localstack`), MockK, AssertJ, ArchUnit, `mock-oauth2-server` (módulo de teste), JaCoCo, Failsafe, `maven-enforcer-plugin`, OWASP dependency-check, CycloneDX.
   - **Front:** React, Vite, TypeScript, React Router, `oidc-client-ts`, `openapi-typescript` e `openapi-fetch`, `react-i18next`, `vite-plugin-pwa`, Vitest, Testing Library, Playwright.

### Fora do plano (por regra)
Nenhum deploy, `apply` de infra nem custo na AWS faz parte do M0. Dockerfile, pipeline de CI e OpenTofu são tratados em spec própria antes do go-live.

## 10. Aprovação
- [x] Aprovado por: Denis em 28/10/2026

## 11. Ajustes da implementação da S1 (28/10/2026)

Registro de onde a realidade divergiu do plano aprovado. **Sem mudança de escopo, de critério de aceite nem de arquitetura.** Nasceu depois da aprovação do plano e foi conferido por Denis no Plannotator em 28/10/2026 (aprovado, sem comentários).

| Plano | Realidade | Motivo |
|---|---|---|
| LocalStack 2026.10.3 | **4.14.0** por digest, sem token | Desde a 2026.03 exige token e plano pago para uso comercial (ADR-0012) |
| Kotlin 2.4.x e Flyway 13.x | Os do **BOM do Boot 4.1.2** (Kotlin 2.3.21, Flyway 12.4.0) | CLAUDE.md, seção 7: versões vêm do BOM (ADR-0003) |
| `spring-boot-starter-web`, `-oauth2-resource-server`, `-flyway` | `spring-boot-starter-webmvc`, `-security-oauth2-resource-server`; Flyway **sem starter** (`flyway-core` e `flyway-database-postgresql`, uma instância por módulo) | Nomes do Boot 4 e uma instância por módulo |
| `micrometer-tracing-bridge-otel` e `opentelemetry-exporter-otlp` avulsos | `spring-boot-starter-opentelemetry` (traz os dois e o registro OTLP de métricas) | Starter do Boot 4 |
| `spring-security-test`, ArchUnit e CycloneDX como dependências | Não declarados: os testes usam tokens reais do `mock-oauth2-server`, o ArchUnit 1.4.2 vem pelo Modulith e o CycloneDX pelo parent do Boot | Evitar dependência desnecessária |
| `springdoc-openapi` | `springdoc-openapi-starter-webmvc-api` 3.2.0, **sem Swagger UI** | Só o contrato é necessário |
| `spring-modulith-starter-jdbc` e `-observability` | **Ainda não adicionados** | Entram com o uso real (T5.4 e quando houver necessidade) |
| JDK Temurin 25 | Zulu 25 no desenvolvimento local (`.sdkmanrc`) | Mesmo OpenJDK; Temurin vai no Dockerfile |
| Trivy na imagem | Bloqueante só para imagens de produção (ADR-0005, CLAUDE.md) | Imagens de desenvolvimento e teste têm achados inevitáveis |
| `MembershipAccessReader` | Port criado na T1.10, **sem adaptador real até a T2.1** (padrão nega tudo) | As tabelas de vínculo e clínica nascem na S2 |
| Teste de catálogo de RLS | Reprova também tabela **sem** `tenant_id` fora de `RlsCatalog.globalTables` | Evitar esquecer o `tenant_id` numa tabela nova |

## 12. Ajustes da implementação da S2 (29/10/2026)

Registro de onde a realidade divergiu do plano aprovado. **Sem mudança de escopo nem de critério de aceite.** Os itens de RLS (linhas 1 a 3) também estão na emenda 1 da ADR-0010. **Status: Aprovada** por Denis em 29/10/2026.

| Plano | Realidade | Motivo |
|---|---|---|
| `clinic`: RLS `id = tenant` ou `platform` | Também legível por quem tem vínculo com a clínica | A tela de escolha de clínica e a validação do `X-Clinic-Id` leem a clínica antes de existir clínica ativa |
| `app_user`: leitura só pelo próprio `sub` ou `platform` | Também legível pela clínica para os usuários com vínculo; `INSERT` dentro da clínica só sem `sub`; `UPDATE` só do `sub` ainda nulo (migração `V4`) | O `CLINIC_ADMIN` precisa convidar, reaproveitar login e reenviar convite sem usar o escopo `platform` (ADR-0010, emenda 1) |
| `membership`: leitura do próprio usuário via `app_user` | Nova coluna `membership.user_sub`, gravada na ativação | Evita ciclo de políticas entre `membership` e `app_user` |
| Id do `app_user` aleatório | Derivado do e-mail normalizado (`UserIds.forEmail`) | O convite grava com `ON CONFLICT DO NOTHING` sem ler nem revelar se o e-mail já existia |
| Entidades JPA | `JdbcTemplate` nas tabelas de `platform` | Evita o plugin `kotlin-noarg`; as regras de ArchUnit sobre `@Entity` e `@Table` seguem sem exercício até algum módulo usar JPA |
| Evento `ClinicCreated` persistido pelo registro do Modulith | Publicado pelo `ApplicationEventPublisher` do Spring | `spring-modulith-starter-jdbc` entra na T5.4 |
| "Branding padrão" na criação da clínica | Ainda não criado; nascerá de um listener de `ClinicCreated` na T3.2 | A tabela `tenant_branding` é da S3 |
| Perfis-modelo com concessões | Os 5 perfis nascem em toda clínica; `CLINIC_ADMIN` com todas as permissões de clínica, os demais com as concessões que os módulos já declararam (hoje nenhuma) | O `registry` ainda não contribui permissões; clínicas criadas antes não recebem concessões retroativas |
| `CLINIC_ADMIN` como perfil comum | Perfil **protegido**: não renomeia, não edita permissões, não apaga (`ROLE_PROTECTED`); o último administrador ativo não sai nem é desativado (`LAST_CLINIC_ADMIN`) | A regra do último administrador identifica o perfil pelo nome |
| Ler perfis e permissões exige `MANAGE_ROLES` | Basta `MANAGE_ROLES` **ou** `MANAGE_USERS` | Quem convida precisa listar perfis e permissões |
| API sem `GET /users` nas tarefas | `GET /users` entregue (lista usuários e perfis da clínica) | Necessário para a tela e para verificar os testes |
| `ErrorKind` sem 502 | `BAD_GATEWAY` (`IDENTITY_PROVIDER_UNAVAILABLE`) no reenvio com o provedor fora | Falha visível, sem mudar o vínculo |
| `AuditTrail.record(user, evento)` | `recordPlatform(autor, clínica, evento)` para ações do `PLATFORM_ADMIN`; `TenantScope.runAsPlatformIn(tenant)` | O `PLATFORM_ADMIN` não tem clínica ativa e grava clínica e perfis na mesma transação |
| Provedor de identidade sempre configurado | `clinic.identity.provider` (`local` ou `cognito`) obrigatório; sem valor a aplicação não sobe | O adaptador falso nunca entra em produção por esquecimento |
| Dependências | `software.amazon.awssdk:cognitoidentityprovider` (BOM 2.57.6) e MockK 1.14.11 (teste), ambas da lista da seção 9.2 | Fora do BOM do Boot |

## 13. Ajustes da implementação da S3 (29/10/2026)

Registro de onde a realidade divergiu do plano. **Status: Aprovada por Denis no Plannotator em 29/10/2026.** A regra de contraste (primeira linha) foi decidida por Denis em 29/10/2026 antes da implementação; o resto é registro.

| Plano | Realidade | Motivo |
|---|---|---|
| Decisão 3 (seção 9.2): contraste >= 4,5 entre a primária e o melhor texto, branco ou preto | **Texto 4,5:1 e fundo branco 3:1.** O `onPrimary` segue sendo o melhor entre branco e preto; a primária também precisa de pelo menos 3:1 contra o fundo branco do app (WCAG 1.4.11). O código de erro continua `BRAND_CONTRAST_INSUFFICIENT` | A regra original nunca reprova: para qualquer cor, o melhor entre branco e preto dá pelo menos 4,58:1 (pior caso na luminância 0,179), então o CA7 não teria como acontecer. A alternativa "sempre texto branco" foi descartada por rejeitar todas as cores claras |
| `BRAND_CONTRAST_INSUFFICIENT` sem status definido | 422 (`COLOR_INVALID`, `DISPLAY_NAME_INVALID` e `VALIDATION_FAILED` são 400) | Regra de negócio violada, não formato |
| Branding padrão na criação da clínica | Listener de `ClinicCreated` cria a marca (nome da clínica, `#0F62FE` e `#6B7280`); falha do listener não derruba a criação e a leitura cai na marca padrão sem gravar | Fecha a pendência da T2.4 |
| `/session` com branding | Entregue: `branding {displayName, primaryColor, secondaryColor, onPrimary, logoUrl, faviconUrl}`; as URLs são assinadas por 10 minutos | RF7 |
| Ler a marca | Qualquer membro da clínica lê; só `MANAGE_BRANDING` grava | O app aplica a marca para todos |
| `tenant_branding` com `UPDATE` e `DELETE` | Só `SELECT`, `INSERT` e `UPDATE`: a marca nunca é apagada | Menor privilégio; o `TenantIsolationIT` ganhou `noDeleteTables` |
| `FileStorage` com adaptador S3 e "local: S3 no LocalStack" | Dois adaptadores: S3 (SSE-S3 explícito em toda gravação, URL assinada de no máximo 15 minutos) e em memória; `clinic.storage.provider` (`s3` ou `memory`) é obrigatório e sem ele a aplicação não sobe. O bucket `clinic-assets` é criado pelo script `docker/localstack/init` (ready.d) | O falso em memória evita subir LocalStack em todo IT; o S3 real é provado no `S3FileStorageIT` |
| Chaves de arquivo | Validadas (`FileKeys`): segmentos simples, sem `..`, sem barra inicial ou dupla, até 512 caracteres | Defesa contra caminho forjado |
| Upload: "valida tipo pelo conteúdo, tamanho e dimensões" | **Logo:** até 1 MiB, lados de 16 a 2048 px. **Favicon:** até 256 KiB, quadrado, lados de 16 a 512 px. Formato por assinatura (PNG, JPEG, WebP lossy, lossless e estendido); só os cabeçalhos são lidos, nada é decodificado. SVG, GIF e HTML dão 415 `IMAGE_TYPE_UNSUPPORTED`; cabeçalho truncado 400 `IMAGE_INVALID`; grande demais 413 `FILE_TOO_LARGE`; dimensões 422 `IMAGE_DIMENSIONS_INVALID`. O contêiner barra acima de 2 MB | Limites a validar; escolhidos como padrão seguro |
| Nome do arquivo no armazenamento | `branding/{clinicId}/{logo\|favicon}-{uuid}.{ext}`, chave nova a cada envio; o antigo é apagado depois de gravar a chave nova | Cache e URL assinada nunca servem o arquivo errado |
| Dependências | `software.amazon.awssdk:s3` e `org.testcontainers:testcontainers-localstack` (teste), da lista da seção 9.2 | Imagem do LocalStack já aprovada na ADR-0012 |


## 14. Ajustes da implementação da S4 (30/10/2026)

Registro de onde a realidade diverge do plano. **Status: Aprovada por Denis no Plannotator em 30/10/2026.** A primeira linha (vínculo do profissional com o usuário) foi decidida por Denis em 30/10/2026 antes da implementação; as demais são registro do que já foi entregue (T4.1 a T4.3).

| Plano | Realidade | Motivo |
|---|---|---|
| `RegisterPractitioner` "cria/vincula o usuário via `platform.api`" e recebe o **e-mail** do profissional | **Só vincula.** O corpo traz `userId` (opcional): o id de um usuário que já é **membro ativo da clínica**, escolhido na lista de `GET /users`. O `registry` confere a pertença por uma leitura nova em `platform.api` (`ClinicMembers.isActiveMember(clinicId, userId)`), sem criar login, sem convidar e sem conceder perfil. O convite continua só em `InviteUser` (`MANAGE_USERS`). O profissional **não guarda e-mail** | Registrar profissional (`MANAGE_PRACTITIONERS`) não pode virar caminho para criar login nem conceder acesso: seria uma mudança de autenticação e de autorização fora da T2.6. Sem convite, não há motivo para guardar o e-mail (minimização de dado pessoal) |
| `practitioner.user_id` "id lógico" sem mais detalhes | Nulo permitido (profissional sem login, por exemplo enfermagem que não usa o sistema); **único por clínica** quando preenchido (`UNIQUE (tenant_id, user_id)`); sem FK entre schemas | Um usuário é um profissional por clínica; o mesmo login em duas clínicas dá dois profissionais (CA11) |
| Violação de regra do domínio sem status definido | **422** com o `code` da regra (`PROCEDURE_NAME_REQUIRED`, `CPF_INVALID`...); nome ou CPF repetido **409**; inexistente ou de outra clínica **404** | Mesmo critério do branding (regra de negócio, não formato) |
| `registry` usa o `CurrentUser` da requisição | `CurrentUser.REQUEST_ATTRIBUTE` em `platform.api` (a mesma chave que o filtro já usava); `ClinicContextFilter.CURRENT_USER` aponta para ele | O `registry` não pode importar `platform.adapter` |
| `Transactions` compartilhado | Cada módulo tem o seu (`registry.application.Transactions`) | O da `platform` fica em `platform.application`, que outros módulos não podem importar |
| Procedimentos: "`MANAGE_PROCEDURES`" | Todas as rotas, inclusive as de leitura, exigem `MANAGE_PROCEDURES`; só o `CLINIC_ADMIN` a recebe por padrão. Sem `DELETE`: o procedimento é inativado (`active = false`) | Como o plano diz; uma permissão de leitura entra quando a agenda (M9) precisar |
| Paciente: invariantes do RF5 | Nome completo = duas palavras ou mais, só letras, espaço, ponto, apóstrofo e hífen, até 150 caracteres; nascimento antes de hoje **no fuso `America/Sao_Paulo`** e a partir de 01/01/1900; WhatsApp precisa ser celular e o telefone fixo não pode ser celular | O RF5 diz "WhatsApp (celular)" e "telefone fixo"; os limites de nome e data são padrão seguro |
| Value objects de dado pessoal | `toString` de `Cpf`, `Email`, `Phone`, `Address` e `Patient` não expõe o dado | Evita vazar dado pessoal em log por acidente (CLAUDE.md, seção 4) |

### Aprovação da seção 14
- [x] Aprovado por: Denis em 30/10/2026 (Plannotator)
