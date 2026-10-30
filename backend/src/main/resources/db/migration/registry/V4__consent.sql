-- Consentimento (RF11, RF12): tipos por clínica, versões imutáveis do texto, aceites com revogação e modelos globais.
-- Sem FK para outro schema. Nada aqui recebe DELETE: histórico de consentimento não se apaga.

-- Tipo de consentimento da clínica. Só um tipo ATIVO por clínica controla mensagens (ConsentRules, no domínio).
create table registry.consent_type
(
    id                 uuid        not null primary key,
    tenant_id          uuid        not null,
    name               text        not null check (length(btrim(name)) between 1 and 120),
    required_on_signup boolean     not null,
    controls_messaging boolean     not null,
    active             boolean     not null default true,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now(),
    unique (tenant_id, id)
);

create unique index consent_type_tenant_name_key on registry.consent_type (tenant_id, lower(name));
create unique index consent_type_one_messaging_key on registry.consent_type (tenant_id) where controls_messaging and active;

select platform.apply_tenant_rls('registry', 'consent_type');

grant select, insert, update on registry.consent_type to clinic_app;

-- Versões do texto: numeradas de 1 a n sem buracos e imutáveis (nem o dono da tabela altera ou apaga).
create table registry.consent_type_version
(
    tenant_id  uuid        not null,
    type_id    uuid        not null,
    number     integer     not null check (number >= 1),
    text       text        not null check (length(btrim(text)) between 1 and 20000),
    created_at timestamptz not null default now(),
    primary key (type_id, number),
    unique (tenant_id, type_id, number),
    foreign key (tenant_id, type_id) references registry.consent_type (tenant_id, id)
);

create function registry.consent_version_next_number() returns trigger
    language plpgsql
as
$$
declare
    expected integer;
begin
    select coalesce(max(number), 0) + 1 into expected from registry.consent_type_version where type_id = new.type_id;
    if new.number <> expected then
        raise exception 'consent version number must be the next one' using errcode = '23514';
    end if;
    return new;
end;
$$;

create trigger consent_version_numbering
    before insert
    on registry.consent_type_version
    for each row
execute function registry.consent_version_next_number();

create function registry.reject_consent_version_change() returns trigger
    language plpgsql
as
$$
begin
    raise exception 'consent_type_version is immutable' using errcode = '55000';
end;
$$;

create trigger consent_version_immutable
    before update or delete
    on registry.consent_type_version
    for each row
execute function registry.reject_consent_version_change();

create trigger consent_version_no_truncate
    before truncate
    on registry.consent_type_version
    for each statement
execute function registry.reject_consent_version_change();

select platform.apply_tenant_rls('registry', 'consent_type_version');

grant select, insert on registry.consent_type_version to clinic_app;

-- Aceite de um paciente, preso à versão que ele viu. A única alteração permitida é registrar a revogação, uma vez.
create table registry.consent_grant
(
    id             uuid        not null primary key,
    tenant_id      uuid        not null,
    patient_id     uuid        not null,
    type_id        uuid        not null,
    version_number integer     not null,
    accepted_at    timestamptz not null,
    channel        text        not null check (channel in ('IN_CLINIC')),
    accepted_by    uuid        not null,
    revoked_at     timestamptz,
    revoked_by     uuid,
    unique (tenant_id, id),
    foreign key (tenant_id, patient_id) references registry.patient (tenant_id, id),
    foreign key (tenant_id, type_id, version_number) references registry.consent_type_version (tenant_id, type_id, number),
    constraint consent_grant_revocation_pair check ((revoked_at is null) = (revoked_by is null)),
    constraint consent_grant_revocation_order check (revoked_at is null or revoked_at >= accepted_at)
);

-- Um aceite vigente por paciente e tipo; depois de revogar, um novo aceite é outro registro.
create unique index consent_grant_one_active_key on registry.consent_grant (tenant_id, patient_id, type_id) where revoked_at is null;
create index consent_grant_patient_idx on registry.consent_grant (tenant_id, patient_id);

create function registry.consent_grant_guard() returns trigger
    language plpgsql
as
$$
begin
    if old.revoked_at is not null then
        raise exception 'consent_grant revocation is final' using errcode = '55000';
    end if;
    if (new.id, new.tenant_id, new.patient_id, new.type_id, new.version_number, new.accepted_at, new.channel, new.accepted_by)
        is distinct from (old.id, old.tenant_id, old.patient_id, old.type_id, old.version_number, old.accepted_at, old.channel, old.accepted_by) then
        raise exception 'consent_grant acceptance data is immutable' using errcode = '55000';
    end if;
    return new;
end;
$$;

create trigger consent_grant_guard
    before update
    on registry.consent_grant
    for each row
execute function registry.consent_grant_guard();

select platform.apply_tenant_rls('registry', 'consent_grant');

grant select, insert on registry.consent_grant to clinic_app;
grant update (revoked_at, revoked_by) on registry.consent_grant to clinic_app;

-- Modelos de consentimento oferecidos pelo PLATFORM_ADMIN. Sem tenant (ADR-0010): qualquer clínica lê; só o escopo
-- `platform` grava. A clínica importa um modelo copiando-o para um tipo seu.
create table registry.consent_template
(
    id                 uuid        not null primary key,
    name               text        not null check (length(btrim(name)) between 1 and 120),
    text               text        not null check (length(btrim(text)) between 1 and 20000),
    required_on_signup boolean     not null,
    controls_messaging boolean     not null,
    active             boolean     not null default true,
    created_at         timestamptz not null default now(),
    updated_at         timestamptz not null default now()
);

create unique index consent_template_name_key on registry.consent_template (lower(name));

alter table registry.consent_template enable row level security;
alter table registry.consent_template force row level security;

create policy consent_template_read on registry.consent_template
    for select
    using (true);

create policy consent_template_insert on registry.consent_template
    for insert
    with check (current_setting('app.scope', true) = 'platform');

create policy consent_template_update on registry.consent_template
    for update
    using (current_setting('app.scope', true) = 'platform')
    with check (current_setting('app.scope', true) = 'platform');

grant select, insert, update on registry.consent_template to clinic_app;
