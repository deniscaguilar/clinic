-- Fila de notificações em tabela (ADR-0011). Guarda a INTENÇÃO de enviar: só ids, canal, tipo, status e controle de
-- retentativa. Nem destinatário nem texto: o envio resolve o contato e monta a mensagem na hora, pela registry.api.
-- Sem FK para outro schema: patient_id e reference_id (o aceite que originou a mensagem) são ids lógicos.
create table notifications.notification
(
    id              uuid        not null primary key,
    tenant_id       uuid        not null,
    patient_id      uuid        not null,
    reference_id    uuid        not null,
    kind            text        not null check (kind in ('CONSENT_ACCEPTED')),
    channel         text        not null check (channel in ('EMAIL', 'WHATSAPP')),
    status          text        not null check (status in ('PENDING', 'SENT', 'FAILED', 'BLOCKED_NO_CONSENT', 'CANCELLED')),
    attempts        integer     not null default 0 check (attempts >= 0),
    next_attempt_at timestamptz,
    last_error_code text check (last_error_code is null or last_error_code ~ '^[A-Z0-9_]{1,64}$'),
    created_at      timestamptz not null default now(),
    updated_at      timestamptz not null default now(),
    unique (tenant_id, id),
    -- só pendente tem hora da próxima tentativa
    constraint notification_due_only_when_pending check ((status = 'PENDING') = (next_attempt_at is not null))
);

-- Uma mensagem por aceite, canal e tipo: o Spring Modulith pode reentregar o evento e o ouvinte é idempotente.
create unique index notification_one_per_reference_key on notifications.notification (tenant_id, reference_id, channel, kind);
create index notification_patient_idx on notifications.notification (tenant_id, patient_id);
create index notification_due_idx on notifications.notification (tenant_id, next_attempt_at) where status = 'PENDING';

select platform.apply_tenant_rls('notifications', 'notification');

-- A aplicação insere, lê e atualiza o andamento; nunca apaga (histórico do que foi ou não enviado).
grant select, insert, update on notifications.notification to clinic_app;
