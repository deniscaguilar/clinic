-- Convite de usuário por um CLINIC_ADMIN (T2.6; emenda da ADR-0010).
--
-- 1) membership.user_sub: cópia do sub do usuário, gravada quando o vínculo é ativado. Serve para o usuário ler os
--    próprios vínculos SEM consultar app_user; assim app_user pode, por sua vez, consultar membership (leitura por
--    tenant) sem criar ciclo de políticas.
-- 2) app_user passa a ser legível pela clínica a que o usuário pertence, e gravável (INSERT sem sub, UPDATE do sub
--    ainda nulo) por quem está no contexto de uma clínica. O sub nunca é aceito de fora no INSERT.
alter table platform.membership add column user_sub text;

-- Preenche os vínculos que já existem (o dono também está sujeito à RLS forçada, então a desligamos só aqui).
alter table platform.membership no force row level security;
alter table platform.app_user no force row level security;
update platform.membership m
set user_sub = u.sub
from platform.app_user u
where u.id = m.user_id
  and m.status <> 'PENDING';
alter table platform.app_user force row level security;
alter table platform.membership force row level security;

create index membership_user_sub_idx on platform.membership (user_sub);

drop policy membership_self on platform.membership;
create policy membership_self on platform.membership
    for select
    using (user_sub is not null and user_sub = nullif(current_setting('app.user_sub', true), ''));

-- A clínica lê os usuários que têm vínculo com ela (a política de membership já limita ao tenant ativo).
create policy app_user_tenant on platform.app_user
    for select
    using (exists (select 1 from platform.membership m where m.user_id = app_user.id));

-- Convidar: dentro de uma clínica, cria-se o usuário sempre sem sub; quem dá o sub é o provedor de identidade.
create policy app_user_invite on platform.app_user
    for insert
    with check (sub is null and nullif(current_setting('app.tenant_id', true), '') is not null);

-- Confirmar o convite: o sub de um usuário da clínica que ainda não tem login pode ser gravado uma única vez.
create policy app_user_set_sub on platform.app_user
    for update
    using (sub is null and exists (select 1 from platform.membership m where m.user_id = app_user.id))
    with check (true);
