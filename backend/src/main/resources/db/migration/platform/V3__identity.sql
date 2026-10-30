-- Identidade, clínicas, vínculos, perfis e permissões (plano do M0, seção 5; ADR-0010).
--
-- Contexto de banco da transação (definido pela aplicação com set_config(..., true)):
--   app.tenant_id: clínica ativa      app.user_sub: sub do próprio usuário      app.scope: 'platform' (só PLATFORM_ADMIN)
-- Sem nenhuma variável, nenhuma linha é visível (falha fechada).

-- Clínica: o próprio id é o tenant. Leitura: a clínica ativa, o escopo platform ou quem tem vínculo com ela
-- (a tela de escolha de clínica e a validação do X-Clinic-Id leem a clínica antes de existir clínica ativa).
create table platform.clinic
(
    id         uuid        not null primary key,
    name       text        not null check (length(btrim(name)) > 0),
    status     text        not null default 'ACTIVE' check (status in ('ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now()
);

-- Usuário (identidade global, sem dado de paciente). `sub` fica nulo até o provedor de identidade criar o login.
create table platform.app_user
(
    id         uuid        not null primary key,
    sub        text unique,
    email      text        not null check (length(btrim(email)) > 0),
    name       text        not null check (length(btrim(name)) > 0),
    created_at timestamptz not null default now()
);
create unique index app_user_email_uidx on platform.app_user (lower(email));

create table platform.platform_admin
(
    user_id    uuid        not null primary key references platform.app_user (id),
    created_at timestamptz not null default now()
);

-- Vínculo usuário x clínica.
create table platform.membership
(
    id         uuid        not null primary key,
    tenant_id  uuid        not null references platform.clinic (id),
    user_id    uuid        not null references platform.app_user (id),
    status     text        not null check (status in ('PENDING', 'ACTIVE', 'INACTIVE')),
    created_at timestamptz not null default now(),
    unique (tenant_id, user_id),
    unique (tenant_id, id)
);
create index membership_user_idx on platform.membership (user_id);

-- Perfil da clínica e seus conjuntos. As chaves compostas (tenant_id, id) impedem ligar linhas de clínicas diferentes.
create table platform.role
(
    id         uuid        not null primary key,
    tenant_id  uuid        not null references platform.clinic (id),
    name       text        not null check (length(btrim(name)) > 0),
    created_at timestamptz not null default now(),
    unique (tenant_id, name),
    unique (tenant_id, id)
);

create table platform.role_permission
(
    tenant_id  uuid not null,
    role_id    uuid not null,
    permission text not null check (length(btrim(permission)) > 0),
    primary key (role_id, permission),
    foreign key (tenant_id, role_id) references platform.role (tenant_id, id) on delete cascade
);

create table platform.membership_role
(
    tenant_id     uuid not null,
    membership_id uuid not null,
    role_id       uuid not null,
    primary key (membership_id, role_id),
    foreign key (tenant_id, membership_id) references platform.membership (tenant_id, id) on delete cascade,
    foreign key (tenant_id, role_id) references platform.role (tenant_id, id)
);
create index membership_role_role_idx on platform.membership_role (role_id);

-- RLS --------------------------------------------------------------------------------------------------------------

-- Perfis, permissões e perfis do vínculo: política padrão por tenant. O escopo platform NÃO os abre.
select platform.apply_tenant_rls('platform', 'role');
select platform.apply_tenant_rls('platform', 'role_permission');
select platform.apply_tenant_rls('platform', 'membership_role');

-- Vínculo: a clínica ativa; o próprio usuário lê os seus (só SELECT); o escopo platform opera entre clínicas.
alter table platform.membership enable row level security;
alter table platform.membership force row level security;

create policy membership_tenant on platform.membership
    using (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    with check (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

create policy membership_self on platform.membership
    for select
    using (exists (select 1
                   from platform.app_user u
                   where u.id = membership.user_id
                     and u.sub = nullif(current_setting('app.user_sub', true), '')));

create policy membership_platform on platform.membership
    using (current_setting('app.scope', true) = 'platform')
    with check (current_setting('app.scope', true) = 'platform');

-- Clínica.
alter table platform.clinic enable row level security;
alter table platform.clinic force row level security;

create policy clinic_select on platform.clinic
    for select
    using (id = nullif(current_setting('app.tenant_id', true), '')::uuid
        or current_setting('app.scope', true) = 'platform'
        or exists (select 1 from platform.membership m where m.tenant_id = clinic.id));

create policy clinic_platform on platform.clinic
    using (current_setting('app.scope', true) = 'platform')
    with check (current_setting('app.scope', true) = 'platform');

-- Usuário: o próprio (pelo sub) ou o escopo platform.
alter table platform.app_user enable row level security;
alter table platform.app_user force row level security;

create policy app_user_self on platform.app_user
    for select
    using (sub = nullif(current_setting('app.user_sub', true), ''));

create policy app_user_platform on platform.app_user
    using (current_setting('app.scope', true) = 'platform')
    with check (current_setting('app.scope', true) = 'platform');

-- Administrador da plataforma: o próprio ou o escopo platform.
alter table platform.platform_admin enable row level security;
alter table platform.platform_admin force row level security;

create policy platform_admin_self on platform.platform_admin
    for select
    using (exists (select 1
                   from platform.app_user u
                   where u.id = platform_admin.user_id
                     and u.sub = nullif(current_setting('app.user_sub', true), '')));

create policy platform_admin_platform on platform.platform_admin
    using (current_setting('app.scope', true) = 'platform')
    with check (current_setting('app.scope', true) = 'platform');

-- Privilégios da aplicação (o papel não é dono de nada). Clínica não se apaga; desativa-se.
grant select, insert, update on platform.clinic to clinic_app;
grant select, insert, update on platform.app_user to clinic_app;
grant select, insert, delete on platform.platform_admin to clinic_app;
grant select, insert, update, delete on platform.membership to clinic_app;
grant select, insert, update, delete on platform.role to clinic_app;
grant select, insert, update, delete on platform.role_permission to clinic_app;
grant select, insert, update, delete on platform.membership_role to clinic_app;
