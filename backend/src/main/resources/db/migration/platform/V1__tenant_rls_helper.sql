-- Convenção de RLS por clínica (ADR-0010).
-- Toda migração que cria tabela de negócio com tenant_id termina chamando:
--   select platform.apply_tenant_rls('<schema>', '<tabela>');
-- Isso liga ENABLE e FORCE ROW LEVEL SECURITY e cria a política padrão "tenant_isolation":
--   USING e WITH CHECK  tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid
-- Sem a variável, nenhuma linha (falha fechada). Tabelas com política própria (ex.: clinic, membership)
-- criam as suas políticas à mão, mas ainda precisam de ENABLE e FORCE; o teste de catálogo cobra isso.
create function platform.apply_tenant_rls(schema_name text, table_name text, tenant_column text default 'tenant_id')
    returns void
    language plpgsql
as
$$
begin
    execute format('alter table %I.%I enable row level security', schema_name, table_name);
    execute format('alter table %I.%I force row level security', schema_name, table_name);
    execute format(
        'create policy tenant_isolation on %I.%I '
            || 'using (%I = nullif(current_setting(''app.tenant_id'', true), '''')::uuid) '
            || 'with check (%I = nullif(current_setting(''app.tenant_id'', true), '''')::uuid)',
        schema_name, table_name, tenant_column, tenant_column);
end;
$$;

revoke all on function platform.apply_tenant_rls(text, text, text) from public;
