# Plano: M9 · Agenda do médico (e da enfermagem)

- **Spec:** `spec.md` (status: Aprovada)
- **Status:** Aprovado

## 1. Visão da solução

O módulo novo `scheduling` guarda os **atendimentos** (`appointment`) e os **parâmetros da agenda** por clínica, com a mesma estrutura dos outros módulos (`api`, `domain`, `application`, `adapter`, `config`), schema Postgres `scheduling` e migrações próprias. Ele só conhece paciente, profissional e procedimento **por id**; nomes, horário de trabalho e duração padrão vêm do `registry` pela `RegistryApi`, que ganha leituras novas.

- **Regras no domínio:** `Appointment` (agregado), `TimeRange`, `ScheduleRules` (cabe no expediente, no fuso `America/Sao_Paulo`, sem atravessar a meia-noite) e `FreeSlots` (cálculo dos horários livres). Sem Spring.
- **Conflito no banco:** restrição de exclusão (`EXCLUDE USING gist`) sobre `tenant_id`, `practitioner_id` e `tstzrange(starts_at, ends_at, '[)')` só para `status = 'SCHEDULED'`. A aplicação também confere antes (erro amigável); o banco decide sob concorrência (`23P01` vira `APPOINTMENT_CONFLICT`, 409). Se a extensão `btree_gist` não puder ser criada pelo usuário de migração, o plano cai para `pg_advisory_xact_lock(hashtext(tenant||practitioner))` na transação (decisão confirmada na T1 das tarefas, antes de seguir).
- **Avisos:** a agenda publica eventos só com ids (`scheduling.api`). O `notifications` os consome e usa a **fila existente**: confirmação na hora e dois lembretes com `next_attempt_at` no horário do lembrete. Cada linha da fila guarda a **revisão** do atendimento; remarcar incrementa a revisão, cancela os pendentes e cria os novos. No envio, o `notifications` pergunta à `scheduling.api` pelo aviso atual e **não envia** se o atendimento não está mais `Marcado` ou se a revisão mudou.
- **Permissões:** o `scheduling` declara as suas pelo `PermissionContributor` (o `platform` monta o catálogo sem conhecer o módulo). O `Authorizer` ganha `has(user, permission): Boolean` (sem lançar nem auditar), porque a regra "só os próprios atendimentos" depende de checar duas permissões.

## 2. Casos de uso (camada application)

| Caso de uso | Entrada | Saída | Regras |
|---|---|---|---|
| `ScheduleAppointment` | profissional, paciente, procedimentos (0..n), início, fim opcional | `Appointment` | `MANAGE_APPOINTMENTS` (RF9); paciente, profissional e procedimentos existem na clínica, ativos (premissa 3); fim padrão = início + soma das durações (ou duração da consulta); cabe no expediente (RF2); sem conflito (RF3); grava, publica `AppointmentScheduled`, audita |
| `RescheduleAppointment` | id, profissional, procedimentos, início, fim opcional | `Appointment` | só `Marcado`; mesmas regras de marcação; `revision + 1`; publica `AppointmentRescheduled`; audita campos alterados |
| `CancelAppointment` | id, motivo | — | só `Marcado`; motivo obrigatório (até 500 caracteres); publica `AppointmentCancelled`; audita |
| `CompleteAppointment` / `MarkNoShow` | id | — | só `Marcado` e `agora >= início`; publica `AppointmentCompleted` / `AppointmentNoShow`; audita |
| `ListAppointments` | profissional, `from`, `to`, página | `PageOf<AppointmentView>` | `VIEW_SCHEDULE` qualquer profissional; `VIEW_OWN_SCHEDULE` só o vinculado (senão 403); período máximo de 31 dias; ordem por início; nomes de paciente e procedimentos via `registry.api`; **um** evento de auditoria por consulta com a contagem |
| `GetAppointment` | id | `AppointmentView` | mesma regra de visibilidade; audita a leitura |
| `FindFreeSlots` | profissional, data, duração (min) | lista de horários | visibilidade como `ListAppointments`; grade de 15 min dentro do expediente, sem conflito com `Marcado`; horários passados do dia atual saem |
| `ListSchedulePractitioners` | — | profissionais ativos | `VIEW_SCHEDULE`: todos; `VIEW_OWN_SCHEDULE`: só o vinculado (alimenta o seletor da tela, que o cadastro de profissionais não serve a quem não tem `MANAGE_PRACTITIONERS`) |
| `GetScheduleSettings` / `UpdateScheduleSettings` | — / valores | `ScheduleSettings` | leitura por quem vê a agenda ou marca; escrita com `MANAGE_SCHEDULE_SETTINGS`; validação do RF10; audita |
| `GetAppointmentNotice` (api) | id | `AppointmentNotice?` | usado pelo `notifications`; sem auditoria de leitura (é o próprio sistema), só ids e dados mínimos da mensagem |

**Mudanças em outros módulos**

- `registry`: `Procedure` ganha `defaultDurationMinutes`; casos de uso de procedimento e controller aceitam o campo; `RegistryApi` ganha as leituras da seção 4.
- `notifications`: casos de uso novos `OnAppointmentScheduled`, `OnAppointmentRescheduled`, `OnAppointmentCancelled` (criam e cancelam linhas da fila) e a composição das mensagens de agenda no envio (`DispatchPending` passa a tratar os tipos novos).
- `platform`: `Authorizer.has`.

## 3. Domínio

**`scheduling.domain`**

- `AppointmentStatus`: `SCHEDULED`, `COMPLETED`, `CANCELLED`, `NO_SHOW`.
- `TimeRange(start: Instant, end: Instant)`: `start < end`; `overlaps(other)` com intervalo semiaberto; duração em minutos.
- `Appointment` (data class com construtor privado, `create`, `restore`): `id`, `practitionerId`, `patientId`, `procedureIds: List<UUID>` (sem repetidos), `range`, `status`, `cancelReason`, `revision`, `createdBy`. Operações: `reschedule(...)` (só `SCHEDULED`, `revision + 1`), `cancel(reason, ...)`, `complete(now)`, `markNoShow(now)`. Transições inválidas lançam `SchedulingException("APPOINTMENT_NOT_SCHEDULED")`; início futuro lança `APPOINTMENT_NOT_STARTED`.
- `ScheduleRules.requireWithinWorkingHours(range, intervals, zone)`: o intervalo cabe inteiro num intervalo de trabalho do dia da semana local e não cruza a meia-noite; senão `OUTSIDE_WORKING_HOURS`.
- `FreeSlots.compute(date, durationMinutes, intervals, busy: List<TimeRange>, now, zone, stepMinutes = 15)`.
- `ScheduleSettings(consultationMinutes, firstReminderHours, secondReminderHours)`: padrão `30`, `24`, `2`; validações do RF10 (`SETTINGS_INVALID`).

**Eventos** (em `scheduling.api`, só ids, instante e revisão): `AppointmentScheduled`, `AppointmentRescheduled`, `AppointmentCancelled`, `AppointmentCompleted`, `AppointmentNoShow`, cada um com `clinicId`, `appointmentId`, `patientId`, `revision`. O `notifications` consome os três primeiros.

**`notifications.domain`:** `NotificationKind` ganha `APPOINTMENT_SCHEDULED`, `APPOINTMENT_REMINDER_1`, `APPOINTMENT_REMINDER_2`, `APPOINTMENT_CANCELLED`; `Notification` ganha `revision` e a operação `cancelPending()` (já existe `CANCELLED` como status).

## 4. Ports

| Port | Direção | Adaptador real | Adaptador local/falso |
|---|---|---|---|
| `AppointmentStore` (`scheduling`) | out | JDBC (`JdbcAppointmentStore`) | fake em memória nos testes |
| `ScheduleSettingsStore` | out | JDBC | fake |
| `PractitionerDirectory` | out | `RegistryPractitionerDirectory` sobre `RegistryApi.getPractitioner`, `findPractitionerByUser`, `listActivePractitioners` | fake |
| `PatientDirectory` | out | sobre `RegistryApi.getPatientNames` | fake |
| `ProcedureDirectory` | out | sobre `RegistryApi.getProcedures` | fake |
| `EventPublisher` | out | Spring Modulith (padrão do `registry`) | fake que registra |
| `Clock`, `Transactions`, `AuditTrail`, `Authorizer` | out | os existentes | os existentes |
| `AppointmentNoticeLookup` (`notifications`) | out | sobre `SchedulingApi.getAppointmentNotice` | fake |
| `MessageComposer` (`notifications`) | out | monta o texto de agenda (clínica, data/hora no fuso, profissional) | o texto de teste do plano |

**`RegistryApi` (acréscimos, só leitura, no contexto da clínica do chamador, falha fechada):**

```kotlin
data class WorkInterval(val dayOfWeek: java.time.DayOfWeek, val start: java.time.LocalTime, val end: java.time.LocalTime)
data class PractitionerInfo(val id: UUID, val name: String, val type: String, val active: Boolean, val userId: UUID?, val workSchedule: List<WorkInterval>)
data class ProcedureInfo(val id: UUID, val name: String, val active: Boolean, val defaultDurationMinutes: Int)
fun getPractitioner(id: UUID): PractitionerInfo?
fun findPractitionerByUser(userId: UUID): PractitionerInfo?
fun listActivePractitioners(): List<PractitionerInfo>
fun getProcedures(ids: Collection<UUID>): Map<UUID, ProcedureInfo>
fun getPatientNames(ids: Collection<UUID>): Map<UUID, String>
```

**`SchedulingApi` (novo, em `scheduling.api`):** `fun getAppointmentNotice(appointmentId: UUID): AppointmentNotice?` com `AppointmentNotice(appointmentId, patientId, practitionerName, startsAt: Instant, status: String, revision: Int, firstReminderHours: Int, secondReminderHours: Int)`.

## 5. Persistência

**`scheduling` (`db/migration/scheduling/V1__appointment.sql`)**, tudo com `tenant_id` e `platform.apply_tenant_rls`; `grant select, insert, update` a `clinic_app` (nunca `delete`):

- `appointment`: `id`, `tenant_id`, `practitioner_id`, `patient_id`, `starts_at timestamptz`, `ends_at timestamptz`, `status` (check nos 4 valores), `cancel_reason` (obrigatório só quando `CANCELLED`, até 500), `revision integer not null default 0`, `created_by uuid`, `created_at`, `updated_at`; `check (starts_at < ends_at)`; `unique (tenant_id, id)`.
- Restrição de exclusão `appointment_no_overlap` (`EXCLUDE USING gist`, descrita na seção 1) e índices `(tenant_id, practitioner_id, starts_at)` e `(tenant_id, patient_id)`.
- `appointment_procedure`: `tenant_id`, `appointment_id`, `procedure_id`, `position smallint`; PK `(appointment_id, procedure_id)`; FK composta `(tenant_id, appointment_id)` para `appointment` (mesmo schema). **Sem FK para outro schema.**
- `schedule_settings`: `tenant_id` PK, `consultation_minutes`, `first_reminder_hours`, `second_reminder_hours` com os checks do RF10; sem linha, valem os padrões.
- `create extension if not exists btree_gist` no começo da migração (extensão confiável a partir do Postgres 13; o Postgres do projeto é o 17).

**`registry` (`V5__procedure_duration.sql`):** `alter table registry.procedure add column default_duration_minutes smallint not null default 30 check (default_duration_minutes between 1 and 480)`.

**`notifications` (`V2__appointment_notifications.sql`):** `revision integer not null default 0`; recria o índice único como `(tenant_id, reference_id, channel, kind, revision)`; amplia o `check` de `kind` com os quatro tipos novos. Migração já aplicada não muda; esta é nova.

## 6. API

Contrato `backend/openapi/openapi.yaml` atualizado junto, com DTOs de nomes únicos (`OpenApiSchemaNamesTest`). Datas e horas em ISO-8601 com deslocamento; `date` de horários livres é a data local (`yyyy-MM-dd`) no fuso da clínica.

| Método e caminho | Uso | Permissão |
|---|---|---|
| `GET /api/v1/schedule/practitioners` | profissionais do seletor | `VIEW_SCHEDULE` ou `VIEW_OWN_SCHEDULE` |
| `GET /api/v1/appointments?practitionerId&from&to&page&size` | agenda do período | idem (próprio ou todos) |
| `GET /api/v1/appointments/{id}` | um atendimento | idem |
| `POST /api/v1/appointments` | marcar | `MANAGE_APPOINTMENTS` |
| `PUT /api/v1/appointments/{id}` | remarcar | `MANAGE_APPOINTMENTS` |
| `POST /api/v1/appointments/{id}/cancel` `{reason}` | cancelar | `MANAGE_APPOINTMENTS` |
| `POST /api/v1/appointments/{id}/complete`, `/no-show` | concluir, faltou | `MANAGE_APPOINTMENTS` |
| `GET /api/v1/schedule/free-slots?practitionerId&date&durationMinutes` | horários livres | como a agenda |
| `GET` e `PUT /api/v1/schedule-settings` | parâmetros | ver: agenda; editar: `MANAGE_SCHEDULE_SETTINGS` |
| `PUT /api/v1/procedures/{id}` e `POST /api/v1/procedures` | ganham `defaultDurationMinutes` | `MANAGE_PROCEDURES` |

Todas exigem `X-Clinic-Id`. Erros em Problem Details com `code` estável: `APPOINTMENT_CONFLICT` (409), `OUTSIDE_WORKING_HOURS` (422), `APPOINTMENT_NOT_FOUND` (404), `APPOINTMENT_NOT_SCHEDULED` (409), `APPOINTMENT_NOT_STARTED` (422), `CANCEL_REASON_REQUIRED` (422), `APPOINTMENT_RANGE_INVALID` (422), `PRACTITIONER_INACTIVE` e `PROCEDURE_INACTIVE` (422), `SETTINGS_INVALID` (422), `SCHEDULE_RANGE_TOO_LARGE` (400); reaproveita `PATIENT_NOT_FOUND`, `PRACTITIONER_NOT_FOUND`, `PROCEDURE_NOT_FOUND`, `PERMISSION_DENIED`. Cada código novo ganha texto pt-BR (o teste do front exige).

**Front:** rotas `/schedule` (agenda por dia e semana com seletor), `/schedule/new` (marcação) e `/schedule/settings`; item de menu "Agenda" por permissão; `duração padrão` no formulário de procedimentos. Busca de paciente na marcação usa `GET /patients?name=` (e a busca por CPF por POST), sempre por id nas rotas.

## 7. Segurança, LGPD e observabilidade

- **Permissões** (catálogo via `PermissionContributor` do `scheduling`): `VIEW_SCHEDULE`, `VIEW_OWN_SCHEDULE`, `MANAGE_APPOINTMENTS`, `MANAGE_SCHEDULE_SETTINGS`. `CLINIC_ADMIN` recebe todas; concessões padrão: `DOCTOR` e `NURSE` `VIEW_OWN_SCHEDULE` + `MANAGE_APPOINTMENTS`; `MANAGER` `VIEW_SCHEDULE`; `STAFF` `VIEW_SCHEDULE` + `MANAGE_APPOINTMENTS`.
- **Checagem no caso de uso**, nunca no controller. "Próprio profissional" = `RegistryApi.findPractitionerByUser(user.userId)`; sem vínculo, só quem tem `VIEW_SCHEDULE` vê.
- **RLS** em `appointment`, `appointment_procedure` e `schedule_settings`; `RlsCatalogIT` passa a cobrar as três; teste de isolamento para cada uma.
- **Auditoria** (`AuditTrail`): `SCHEDULE_APPOINTMENT`, `RESCHEDULE_APPOINTMENT`, `CANCEL_APPOINTMENT`, `COMPLETE_APPOINTMENT`, `MARK_NO_SHOW`, `READ_APPOINTMENT`, `LIST_APPOINTMENTS` (contagem), `UPDATE_SCHEDULE_SETTINGS`; `details` só com nomes de campos.
- **Dado de paciente:** a lista traz só o nome; o atendimento guarda só o id; nada de paciente em log, trace ou métrica. As mensagens levam clínica, data, hora e profissional. O motivo do cancelamento é texto livre da equipe: não vai para log, trace nem para a mensagem ao paciente.
- **Observabilidade:** spans dos casos de uso com `tenant_id` e `user_id`; contadores `scheduling.appointments` por situação (sem ids).

## 8. Estratégia de testes

- **Unitários (`*Test`, fakes, sem Spring):** `Appointment` (transições, revisão, motivo), `ScheduleRules` (cabe, encosta nas bordas, cruza meia-noite, dia sem expediente, dois intervalos no dia), `FreeSlots` (grade, conflitos, passado), `ScheduleSettings`, cada caso de uso (permissões inclusive `VIEW_OWN` sem vínculo, conflito, inativos, fim padrão por soma e por consulta) e, no `notifications`, criação e cancelamento de linhas e composição das mensagens. Cobertura >= 85% em `domain` + `application`.
- **Integração (`*IT`):** migrações (`ModuleMigrationIT`), restrição de exclusão (encostar permitido, sobrepor recusado, `CANCELLED` libera), **concorrência** (duas marcações simultâneas, uma vence), API completa por MockMvc (CA1 a CA18, CA25 a CA27), fluxo de avisos de ponta a ponta com e-mail e WhatsApp falsos (CA19 a CA24), duração do procedimento (CA26).
- **Isolamento de tenant:** `appointment`, `appointment_procedure` e `schedule_settings` (CA29), mais `RlsCatalogIT`.
- **Arquitetura:** `ModularityTest` com `scheduling` (depende de `platform`, `registry`) e `notifications` (passa a depender de `scheduling`); `docs/architecture.md` atualizado.
- **Front (Vitest):** agenda, marcação (horários livres, erros por `code`), ações de situação, "minha agenda", parâmetros, duração no procedimento, navegação por permissão, textos pt-BR; **Playwright:** o fluxo crítico ganha "marcar um atendimento" (CA30).
- **Mutação:** mutantes em scratchpad nas regras de expediente, conflito, transições, permissão do próprio profissional, cancelamento de avisos e checagem de revisão; sobreviventes viram teste.

## 9. Riscos e impactos em outros módulos

- **`registry`:** migração e campo novos em procedimento (existentes ficam com 30 min); `RegistryApi` cresce (sem quebrar quem usa).
- **`notifications`:** novo consumidor de `scheduling.api`; migração da fila (índice único novo, `kind`); `DispatchPending` ganha ramo para os tipos de agenda. É a mudança mais delicada: a fila é de dado de paciente em mensagem, e a decisão de Dênis está registrada na spec (seção 8).
- **`platform`:** `Authorizer.has` (fakes de teste ajustados).
- **Arquitetura:** `notifications` passa a depender de `scheduling` (`docs/architecture.md` e `ModularityTest`); `scheduling` não depende de `notifications`, então não há ciclo; `dashboard` e `inventory` já consomem `scheduling` no mapa.
- **Risco: `btree_gist`.** Se o usuário de migração não puder criar a extensão, o plano cai para bloqueio consultivo (seção 1); o teste de concorrência vale nos dois casos.
- **Risco: remarcação e fila.** Lembrete pendente de revisão antiga é cancelado e o envio confere a revisão; o teste cobre lembrete que vence entre o evento e o cancelamento.
- **Risco: fuso.** O Brasil não usa horário de verão hoje, mas o cálculo do expediente e dos horários livres usa `ZoneId` injetado e `Clock`, nunca o fuso do servidor; um teste roda com o servidor em outro fuso e confere o mesmo resultado.
- **Perfis de clínicas existentes** não ganham as permissões novas (premissa 6 da spec). Não há clínica em produção; o `CLINIC_ADMIN` as atribui nas de teste. Um mecanismo de sincronização de perfis-modelo fica como tarefa futura, útil também ao M4.
- **Fora do plano (por regra):** nenhum deploy, infraestrutura ou custo; provedor real de WhatsApp continua o falso.

## 10. Aprovação

- [x] Aprovado por: Dênis, via Plannotator, em 30/10/2026
