-- Identidade visual da clínica (RF7). Uma linha por clínica; o tenant é a chave. Cores sempre em #RRGGBB maiúsculo.
-- on_primary é calculado pela aplicação (melhor texto, branco ou preto, sobre a cor primária) e guardado para o front.
-- logo_key e favicon_key são chaves do armazenamento de arquivos (S3), nunca URLs.
create table platform.tenant_branding
(
    tenant_id       uuid        not null primary key references platform.clinic (id),
    display_name    text        not null check (length(btrim(display_name)) between 1 and 120),
    primary_color   text        not null check (primary_color ~ '^#[0-9A-F]{6}$'),
    secondary_color text        not null check (secondary_color ~ '^#[0-9A-F]{6}$'),
    on_primary      text        not null check (on_primary in ('#FFFFFF', '#000000')),
    logo_key        text,
    favicon_key     text,
    updated_at      timestamptz not null default now()
);

select platform.apply_tenant_rls('platform', 'tenant_branding');

grant select, insert, update on platform.tenant_branding to clinic_app;
