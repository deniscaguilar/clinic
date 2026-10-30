package com.endoacademy.clinic.support

import org.springframework.jdbc.core.JdbcTemplate

/**
 * Varre o catálogo do Postgres e lista tabelas dos schemas dos módulos que quebram a convenção de RLS (CA10):
 *  1. tabela com coluna `tenant_id` precisa ter RLS habilitada, forçada e ao menos uma política;
 *  2. tabela sem `tenant_id` precisa estar na lista explícita de tabelas globais (cada uma com justificativa),
 *     para que esquecer o `tenant_id` numa tabela nova não passe despercebido.
 * Só tabelas (`r` e `p`); views ficam de fora.
 */
object RlsCatalog {
    val schemas = listOf("platform", "registry", "notifications", "eventing")

    /** Tabelas sem `tenant_id` por desenho. Formato `schema.tabela`. Cada entrada nova pede justificativa no PR. */
    val globalTables: Set<String> by lazy { baseGlobalTables + rlsGlobalTables }
    private val baseGlobalTables = setOf(
        "platform.flyway_schema_history",
        "registry.flyway_schema_history",
        "notifications.flyway_schema_history",
        "eventing.flyway_schema_history",
        // Registro de publicações do Spring Modulith: infraestrutura, eventos só com ids, sem dado pessoal.
        "eventing.event_publication",
    )

    /**
     * Tabelas sem `tenant_id` que guardam dado de identidade e, por isso, também exigem RLS habilitada, forçada e
     * com política (ADR-0010): a `clinic` usa o próprio `id` como tenant; `app_user` e `platform_admin` são globais.
     */
    val rlsGlobalTables = setOf(
        "platform.clinic",
        "platform.app_user",
        "platform.platform_admin",
        "registry.consent_template",
    )

    private const val WITH_TENANT = """
        select n.nspname || '.' || c.relname as name, c.relrowsecurity as enabled, c.relforcerowsecurity as forced,
               (select count(*) from pg_policy p where p.polrelid = c.oid) as policies
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        join pg_attribute a on a.attrelid = c.oid and a.attname = 'tenant_id' and a.attnum > 0 and not a.attisdropped
        where c.relkind in ('r', 'p') and n.nspname = any (?)
        order by 1
    """

    private const val RLS_OF = """
        select n.nspname || '.' || c.relname as name, c.relrowsecurity as enabled, c.relforcerowsecurity as forced,
               (select count(*) from pg_policy p where p.polrelid = c.oid) as policies
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        where c.relkind in ('r', 'p') and n.nspname || '.' || c.relname = any (?)
        order by 1
    """

    private const val WITHOUT_TENANT = """
        select n.nspname || '.' || c.relname
        from pg_class c
        join pg_namespace n on n.oid = c.relnamespace
        where c.relkind in ('r', 'p') and n.nspname = any (?)
          and not exists (select 1 from pg_attribute a
                          where a.attrelid = c.oid and a.attname = 'tenant_id' and a.attnum > 0 and not a.attisdropped)
        order by 1
    """

    fun violations(jdbc: JdbcTemplate): List<String> {
        val schemaArray = schemas.toTypedArray()
        val problems = mutableListOf<String>()
        jdbc.query(WITH_TENANT, { rs, _ ->
            val name = rs.getString("name")
            if (!rs.getBoolean("enabled")) problems += "$name: RLS não habilitada"
            if (!rs.getBoolean("forced")) problems += "$name: RLS não forçada (FORCE ROW LEVEL SECURITY)"
            if (rs.getInt("policies") == 0) problems += "$name: sem política de RLS"
        }, schemaArray as Any)
        jdbc.query(RLS_OF, { rs, _ ->
            val name = rs.getString("name")
            if (!rs.getBoolean("enabled")) problems += "$name: RLS não habilitada"
            if (!rs.getBoolean("forced")) problems += "$name: RLS não forçada (FORCE ROW LEVEL SECURITY)"
            if (rs.getInt("policies") == 0) problems += "$name: sem política de RLS"
        }, rlsGlobalTables.toTypedArray() as Any)
        jdbc.query(WITHOUT_TENANT, { rs, _ -> rs.getString(1) }, schemaArray as Any)
            .filter { it !in globalTables }
            .forEach { problems += "$it: sem tenant_id e fora da lista de tabelas globais" }
        return problems
    }
}
