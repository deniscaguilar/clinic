-- Procedimentos oferecidos pela clínica (RF6). Não se apagam: saem de uso ficando inativos (active = false),
-- por isso a aplicação não recebe DELETE. O nome é único por clínica sem diferenciar maiúsculas e minúsculas.
-- Sem FK para outro schema (CLAUDE.md, seção 3): tenant_id é o id lógico da clínica.
create table registry.procedure
(
    id          uuid        not null primary key,
    tenant_id   uuid        not null,
    name        text        not null check (length(btrim(name)) between 1 and 120),
    description text check (description is null or length(btrim(description)) between 1 and 500),
    active      boolean     not null default true,
    created_at  timestamptz not null default now(),
    updated_at  timestamptz not null default now(),
    unique (tenant_id, id)
);

create unique index procedure_tenant_name_key on registry.procedure (tenant_id, lower(name));

select platform.apply_tenant_rls('registry', 'procedure');

grant select, insert, update on registry.procedure to clinic_app;
