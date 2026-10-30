package com.endoacademy.clinic.support

import org.springframework.jdbc.core.JdbcTemplate
import java.util.UUID

/**
 * Como inserir uma linha de teste numa tabela com `tenant_id`, para o `TenantIsolationIT` genérico.
 * **Toda tabela nova com `tenant_id` precisa registrar aqui o seu inserter**; sem isso o teste falha
 * (CLAUDE.md, seção 4: toda entidade nova exige teste de isolamento entre tenants).
 * O inserter roda dentro de uma transação já no contexto do tenant dono da linha.
 */
object TenantFixtures {
    fun interface RowInserter {
        fun insert(jdbc: JdbcTemplate, tenantId: UUID)
    }

    /** Chave: `schema.tabela`. */
    val inserters: MutableMap<String, RowInserter> = mutableMapOf(
        "platform.membership" to RowInserter { jdbc, tenantId -> newMembership(jdbc, tenantId) },
        "platform.role" to RowInserter { jdbc, tenantId -> newRole(jdbc, tenantId) },
        "platform.role_permission" to RowInserter { jdbc, tenantId ->
            jdbc.update(
                "insert into platform.role_permission (tenant_id, role_id, permission) values (?, ?, 'VIEW_PATIENTS')",
                tenantId,
                newRole(jdbc, tenantId),
            )
        },
        "platform.membership_role" to RowInserter { jdbc, tenantId ->
            jdbc.update(
                "insert into platform.membership_role (tenant_id, membership_id, role_id) values (?, ?, ?)",
                tenantId,
                newMembership(jdbc, tenantId),
                newRole(jdbc, tenantId),
            )
        },
        "platform.tenant_branding" to RowInserter { jdbc, tenantId ->
            ensureClinic(jdbc, tenantId)
            jdbc.update(
                "insert into platform.tenant_branding (tenant_id, display_name, primary_color, secondary_color, on_primary) " +
                    "values (?, 'Marca de teste', '#0F62FE', '#6B7280', '#FFFFFF') on conflict do nothing",
                tenantId,
            )
        },
        "registry.patient" to RowInserter { jdbc, tenantId -> newPatient(jdbc, tenantId) },
        "registry.consent_type" to RowInserter { jdbc, tenantId -> newConsentType(jdbc, tenantId) },
        "registry.consent_type_version" to RowInserter { jdbc, tenantId ->
            val typeId = newConsentType(jdbc, tenantId, withFirstVersion = false)
            jdbc.update("insert into registry.consent_type_version (tenant_id, type_id, number, text) values (?, ?, 1, 'Texto de teste')", tenantId, typeId)
        },
        "registry.consent_grant" to RowInserter { jdbc, tenantId ->
            jdbc.update(
                "insert into registry.consent_grant (id, tenant_id, patient_id, type_id, version_number, accepted_at, channel, accepted_by) " +
                    "values (?, ?, ?, ?, 1, now(), 'IN_CLINIC', ?)",
                UUID.randomUUID(),
                tenantId,
                newPatient(jdbc, tenantId),
                newConsentType(jdbc, tenantId),
                UUID.randomUUID(),
            )
        },
        "notifications.notification" to RowInserter { jdbc, tenantId ->
            jdbc.update(
                "insert into notifications.notification (id, tenant_id, patient_id, reference_id, kind, channel, status, next_attempt_at) " +
                    "values (?, ?, ?, ?, 'CONSENT_ACCEPTED', 'EMAIL', 'PENDING', now())",
                UUID.randomUUID(),
                tenantId,
                UUID.randomUUID(),
                UUID.randomUUID(),
            )
        },
        "registry.practitioner" to RowInserter { jdbc, tenantId -> newPractitioner(jdbc, tenantId) },
        "registry.practitioner_work_schedule" to RowInserter { jdbc, tenantId ->
            jdbc.update(
                "insert into registry.practitioner_work_schedule (tenant_id, practitioner_id, day_of_week, start_time, end_time) values (?, ?, 1, '08:00', '12:00')",
                tenantId,
                newPractitioner(jdbc, tenantId),
            )
        },
        "registry.procedure" to RowInserter { jdbc, tenantId ->
            jdbc.update("insert into registry.procedure (id, tenant_id, name) values (?, ?, ?)", UUID.randomUUID(), tenantId, "Procedimento de teste ${UUID.randomUUID()}")
        },
        "platform.audit_event" to RowInserter { jdbc, tenantId ->
            jdbc.update(
                "insert into platform.audit_event (id, tenant_id, occurred_at, action, entity_type, outcome, channel) " +
                    "values (?, ?, now(), 'TEST', 'TEST', 'SUCCESS', 'API')",
                UUID.randomUUID(),
                tenantId,
            )
        },
    )

    /**
     * Tabelas com `tenant_id` que o escopo `platform` pode abrir (ADR-0010). Qualquer outra, em especial as de
     * paciente, continua fechada para o escopo `platform`. Cada entrada se justifica pela ADR.
     */
    /** Tabelas append-only: a aplicação não tem UPDATE nem DELETE, então tentar alterar dá "permission denied". */
    val appendOnlyTables: Set<String> = setOf("platform.audit_event", "registry.consent_type_version")

    /** Tabelas em que a aplicação pode alterar mas nunca apagar (privilégio `DELETE` não concedido). */
    val noDeleteTables: Set<String> = setOf("platform.tenant_branding", "registry.procedure", "registry.practitioner", "registry.patient", "registry.consent_type", "registry.consent_grant", "notifications.notification")

    /**
     * Tabelas em que a aplicação não tem `UPDATE` sobre `tenant_id` (privilégio não concedido, ou concedido só em colunas
     * específicas, como a revogação do aceite): tentar alterar `tenant_id` dá `permission denied`.
     */
    val noUpdateTables: Set<String> = setOf("registry.practitioner_work_schedule", "registry.consent_grant")

    val platformScopeTables: Set<String> = setOf(
        "platform.clinic",
        "platform.membership",
        "platform.audit_event",
    )

    /**
     * As linhas-pai (`clinic`, `app_user`) só se gravam no escopo `platform`. Os inserters elevam o escopo só para
     * isso e o devolvem logo depois, de modo que a linha filha é gravada sob o contexto real do teste.
     */
    private fun asPlatform(jdbc: JdbcTemplate, block: () -> Unit) {
        jdbc.queryForObject("select set_config('app.scope', 'platform', true)", String::class.java)
        try {
            block()
        } finally {
            jdbc.queryForObject("select set_config('app.scope', '', true)", String::class.java)
        }
    }

    fun ensureClinic(jdbc: JdbcTemplate, tenantId: UUID) = asPlatform(jdbc) {
        jdbc.update("insert into platform.clinic (id, name) values (?, 'Clínica de teste') on conflict do nothing", tenantId)
    }

    fun newPatient(jdbc: JdbcTemplate, tenantId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update(
            "insert into registry.patient (id, tenant_id, full_name, cpf, birth_date, whatsapp, zip_code, street, street_number, district, city, state) " +
                "values (?, ?, 'Paciente de teste', ?, date '1990-01-01', '11987654321', '01310100', 'Rua A', '1', 'Centro', 'São Paulo', 'SP')",
            id,
            tenantId,
            randomValidCpf(),
        )
        return id
    }

    /** CPF com dígitos verificadores corretos (o domínio revalida o CPF ao ler), sem repetir o mesmo dígito. */
    fun randomValidCpf(): String {
        fun digit(digits: String, length: Int): Int {
            val rest = (digits.take(length).withIndex().sumOf { (i, c) -> (c - '0') * (length + 1 - i) } * 10) % 11
            return if (rest == 10) 0 else rest
        }
        while (true) {
            val base = (100_000_000L + (UUID.randomUUID().mostSignificantBits and Long.MAX_VALUE) % 899_999_999L).toString()
            if (base.all { it == base[0] }) continue
            val first = digit(base, 9)
            val second = digit(base + first, 10)
            return "$base$first$second"
        }
    }

    /** Tipo de consentimento com um nome único; por padrão já com a versão 1. */
    fun newConsentType(jdbc: JdbcTemplate, tenantId: UUID, withFirstVersion: Boolean = true): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into registry.consent_type (id, tenant_id, name, required_on_signup, controls_messaging) values (?, ?, ?, false, false)", id, tenantId, "Tipo de teste $id")
        if (withFirstVersion) jdbc.update("insert into registry.consent_type_version (tenant_id, type_id, number, text) values (?, ?, 1, 'Texto de teste')", tenantId, id)
        return id
    }

    fun newPractitioner(jdbc: JdbcTemplate, tenantId: UUID): UUID {
        val id = UUID.randomUUID()
        jdbc.update("insert into registry.practitioner (id, tenant_id, type, name) values (?, ?, 'DOCTOR', 'Profissional de teste')", id, tenantId)
        return id
    }

    fun newUser(jdbc: JdbcTemplate): UUID {
        val id = UUID.randomUUID()
        asPlatform(jdbc) {
            jdbc.update("insert into platform.app_user (id, sub, email, name) values (?, ?, ?, 'Usuário de teste')", id, "sub-$id", "$id@example.test")
        }
        return id
    }

    fun newMembership(jdbc: JdbcTemplate, tenantId: UUID): UUID {
        ensureClinic(jdbc, tenantId)
        val userId = newUser(jdbc)
        val id = UUID.randomUUID()
        jdbc.update("insert into platform.membership (id, tenant_id, user_id, status, user_sub) values (?, ?, ?, 'ACTIVE', ?)", id, tenantId, userId, "sub-$userId")
        return id
    }

    fun newRole(jdbc: JdbcTemplate, tenantId: UUID): UUID {
        ensureClinic(jdbc, tenantId)
        val id = UUID.randomUUID()
        jdbc.update("insert into platform.role (id, tenant_id, name) values (?, ?, ?)", id, tenantId, "role-$id")
        return id
    }
}
