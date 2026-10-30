-- Trilha de auditoria append-only (CLAUDE.md, seção 4; plano, seção 5).
-- tenant_id é nulo para ação sem clínica (ex.: ação do PLATFORM_ADMIN, login antes de escolher a clínica).
-- details guarda só NOMES de campos; nunca valores nem dado de paciente.
create table platform.audit_event
(
    id          uuid        not null primary key,
    tenant_id   uuid,
    occurred_at timestamptz not null,
    user_id     uuid,
    action      text        not null,
    entity_type text        not null,
    entity_id   text,
    outcome     text        not null check (outcome in ('SUCCESS', 'DENIED', 'FAILURE')),
    channel     text        not null,
    ip          text,
    details     jsonb       not null default '[]'::jsonb
);

create index audit_event_tenant_occurred_idx on platform.audit_event (tenant_id, occurred_at desc);
create index audit_event_entity_idx on platform.audit_event (tenant_id, entity_type, entity_id);

-- RLS ligada e forçada, com políticas próprias (tenant_id pode ser nulo).
alter table platform.audit_event enable row level security;
alter table platform.audit_event force row level security;

-- Ler: a própria clínica; o escopo platform lê tudo (ADR-0010).
create policy audit_event_select on platform.audit_event
    for select
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
        or current_setting('app.scope', true) = 'platform');

-- Gravar: só para a clínica ativa, ou sem clínica.
create policy audit_event_insert on platform.audit_event
    for insert
    with check (tenant_id is null
        or tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- A aplicação só insere e lê. UPDATE, DELETE e TRUNCATE nunca são concedidos.
revoke all on platform.audit_event from public;
grant select, insert on platform.audit_event to clinic_app;

-- Segunda barreira: mesmo um papel com privilégio (inclusive o dono) é recusado.
create function platform.reject_audit_event_change()
    returns trigger
    language plpgsql
as
$$
begin
    raise exception 'audit_event é append-only (% recusado)', tg_op using errcode = '42501';
end;
$$;

create trigger audit_event_no_row_change
    before update or delete
    on platform.audit_event
    for each row
execute function platform.reject_audit_event_change();

create trigger audit_event_no_truncate
    before truncate
    on platform.audit_event
    for each statement
execute function platform.reject_audit_event_change();
