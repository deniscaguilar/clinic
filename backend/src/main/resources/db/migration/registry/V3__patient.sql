-- Paciente da clínica (RF5): dado pessoal. Isolado por clínica pela RLS; o escopo `platform` nunca abre esta tabela.
-- O CPF fica em texto (11 dígitos), com criptografia em repouso do RDS e RLS (plano, decisão 4); é único por clínica.
-- Não se apaga: a retenção e a anonimização dependem de definição jurídica (pergunta em aberto), então a aplicação não
-- recebe DELETE. Os CHECKs repetem as regras do domínio como última defesa (formato de CPF, telefone, CEP, UF e contato).
create table registry.patient
(
    id            uuid        not null primary key,
    tenant_id     uuid        not null,
    full_name     text        not null check (length(btrim(full_name)) between 3 and 150),
    cpf           text        not null check (cpf ~ '^[0-9]{11}$'),
    birth_date    date        not null check (birth_date >= date '1900-01-01'),
    whatsapp      text check (whatsapp is null or whatsapp ~ '^[1-9][1-9]9[0-9]{8}$'),
    email         text check (email is null or length(email) between 3 and 254),
    landline      text check (landline is null or landline ~ '^[1-9][1-9][2-5][0-9]{7}$'),
    zip_code      text        not null check (zip_code ~ '^[0-9]{8}$'),
    street        text        not null check (length(btrim(street)) between 1 and 120),
    street_number text        not null check (length(btrim(street_number)) between 1 and 20),
    complement    text check (complement is null or length(btrim(complement)) between 1 and 120),
    district      text        not null check (length(btrim(district)) between 1 and 120),
    city          text        not null check (length(btrim(city)) between 1 and 120),
    state         text        not null check (state in ('AC', 'AL', 'AP', 'AM', 'BA', 'CE', 'DF', 'ES', 'GO', 'MA', 'MT', 'MS', 'MG', 'PA',
                                                        'PB', 'PR', 'PE', 'PI', 'RJ', 'RN', 'RS', 'RO', 'RR', 'SC', 'SP', 'SE', 'TO')),
    created_at    timestamptz not null default now(),
    updated_at    timestamptz not null default now(),
    constraint patient_contact_required check (whatsapp is not null or email is not null),
    unique (tenant_id, id),
    constraint patient_tenant_cpf_key unique (tenant_id, cpf)
);

create index patient_tenant_name_idx on registry.patient (tenant_id, lower(full_name), id);

select platform.apply_tenant_rls('registry', 'patient');

grant select, insert, update on registry.patient to clinic_app;
