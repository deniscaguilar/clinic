package com.endoacademy.clinic.registry

import com.endoacademy.clinic.platform.api.TenantScope
import com.endoacademy.clinic.support.DatabaseIT
import com.endoacademy.clinic.support.PostgresContainer
import com.endoacademy.clinic.support.TenantFixtures
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.support.TransactionTemplate
import java.sql.DriverManager
import java.util.UUID

/** Esquema de consentimento no Postgres real: versões imutáveis, aceite com revogação única e modelos globais (T5.2). */
class ConsentSchemaIT : DatabaseIT() {

    @Autowired
    lateinit var jdbc: JdbcTemplate

    @Autowired
    lateinit var transactionManager: PlatformTransactionManager

    private val tx get() = TransactionTemplate(transactionManager)

    private fun inClinic(clinic: UUID, block: () -> Unit) = TenantScope.runAs(clinic) { tx.executeWithoutResult { block() } }

    private fun asPlatform(block: () -> Unit) = TenantScope.runAsPlatform { tx.executeWithoutResult { block() } }

    /** Roda como o dono da tabela (`clinic_migrator`), que também está sob RLS forçada: sem clínica ele não vê linha nenhuma. */
    private fun <T> ownerSql(clinic: UUID? = null, block: (java.sql.Statement) -> T): T =
        DriverManager.getConnection(PostgresContainer.instance.jdbcUrl, "clinic_migrator", PostgresContainer.MIGRATOR_PASSWORD).use { c ->
            c.createStatement().use { st ->
                if (clinic != null) st.execute("select set_config('app.tenant_id', '$clinic', false)")
                block(st)
            }
        }

    private fun newType(clinic: UUID, name: String = "Tipo ${UUID.randomUUID()}", messaging: Boolean = false, active: Boolean = true): UUID {
        val id = UUID.randomUUID()
        inClinic(clinic) {
            jdbc.update(
                "insert into registry.consent_type (id, tenant_id, name, required_on_signup, controls_messaging, active) values (?, ?, ?, true, ?, ?)",
                id, clinic, name, messaging, active,
            )
            jdbc.update("insert into registry.consent_type_version (tenant_id, type_id, number, text) values (?, ?, 1, 'Texto 1')", clinic, id)
        }
        return id
    }

    private fun newPatient(clinic: UUID): UUID {
        var id: UUID? = null
        inClinic(clinic) { id = TenantFixtures.newPatient(jdbc, clinic) }
        return id!!
    }

    private fun accept(clinic: UUID, patient: UUID, type: UUID, version: Int = 1): UUID {
        val id = UUID.randomUUID()
        inClinic(clinic) {
            jdbc.update(
                "insert into registry.consent_grant (id, tenant_id, patient_id, type_id, version_number, accepted_at, channel, accepted_by) values (?, ?, ?, ?, ?, now(), 'IN_CLINIC', ?)",
                id, clinic, patient, type, version, UUID.randomUUID(),
            )
        }
        return id
    }

    private fun count(clinic: UUID, sql: String, vararg args: Any): Int =
        TenantScope.runAs(clinic) { tx.execute { jdbc.queryForObject(sql, Int::class.java, *args) } }!!

    // --- consent_type ---

    @Test
    fun `the type name is unique per clinic ignoring case and another clinic may reuse it`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        newType(a, "Cadastro e mensagens")
        assertThatThrownBy { newType(a, "CADASTRO E MENSAGENS") }.rootCause().hasMessageContaining("consent_type_tenant_name_key")
        newType(b, "Cadastro e mensagens")
    }

    @Test
    fun `only one active type per clinic controls messaging, inactive ones do not count`() {
        val clinic = UUID.randomUUID()
        val first = newType(clinic, messaging = true)
        assertThatThrownBy { newType(clinic, messaging = true) }.rootCause().hasMessageContaining("consent_type_one_messaging_key")
        newType(clinic, messaging = false)
        inClinic(clinic) { jdbc.update("update registry.consent_type set active = false where id = ?", first) }
        newType(clinic, messaging = true)
        newType(UUID.randomUUID(), messaging = true) // outra clínica
    }

    // --- consent_type_version ---

    @Test
    fun `versions are numbered one after the other, with no gaps and no repetition`() {
        val clinic = UUID.randomUUID()
        val type = newType(clinic)
        fun insertVersion(number: Int) = inClinic(clinic) {
            jdbc.update("insert into registry.consent_type_version (tenant_id, type_id, number, text) values (?, ?, ?, 'Texto')", clinic, type, number)
        }
        assertThatThrownBy { insertVersion(3) }.rootCause().hasMessageContaining("next one")
        assertThatThrownBy { insertVersion(1) }.rootCause().hasMessageContaining("next one")
        assertThatThrownBy { insertVersion(0) }.rootCause().hasMessageContaining("next one")
        insertVersion(2)
        insertVersion(3)
        assertThat(count(clinic, "select count(*) from registry.consent_type_version where type_id = ?", type)).isEqualTo(3)
    }

    @Test
    fun `a stored version cannot be changed or deleted, not even by the table owner (CA15)`() {
        val clinic = UUID.randomUUID()
        val type = newType(clinic)
        // a aplicação nem tem o privilégio
        assertThatThrownBy { inClinic(clinic) { jdbc.update("update registry.consent_type_version set text = 'Outro' where type_id = ?", type) } }
            .rootCause().hasMessageContaining("permission denied")
        assertThatThrownBy { inClinic(clinic) { jdbc.update("delete from registry.consent_type_version where type_id = ?", type) } }
            .rootCause().hasMessageContaining("permission denied")
        // o dono da tabela esbarra no gatilho
        assertThatThrownBy { ownerSql(clinic) { it.executeUpdate("update registry.consent_type_version set text = 'Outro' where type_id = '$type'") } }.hasMessageContaining("immutable")
        assertThatThrownBy { ownerSql(clinic) { it.executeUpdate("delete from registry.consent_type_version where type_id = '$type'") } }.hasMessageContaining("immutable")
        assertThatThrownBy { ownerSql { it.execute("truncate registry.consent_type_version cascade") } }.hasMessageContaining("immutable")
        assertThat(count(clinic, "select count(*) from registry.consent_type_version where type_id = ? and text = 'Texto 1'", type)).isEqualTo(1)
    }

    @Test
    fun `a version needs a type of the same clinic`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val bareTypeOfA = UUID.randomUUID()
        inClinic(a) {
            jdbc.update("insert into registry.consent_type (id, tenant_id, name, required_on_signup, controls_messaging) values (?, ?, ?, false, false)", bareTypeOfA, a, "Sem versão $bareTypeOfA")
        }
        assertThatThrownBy {
            inClinic(b) { jdbc.update("insert into registry.consent_type_version (tenant_id, type_id, number, text) values (?, ?, 1, 'Texto')", b, bareTypeOfA) }
        }.rootCause().hasMessageContaining("violates foreign key constraint")
        inClinic(a) { jdbc.update("insert into registry.consent_type_version (tenant_id, type_id, number, text) values (?, ?, 1, 'Texto')", a, bareTypeOfA) }
    }

    // --- consent_grant ---

    @Test
    fun `an acceptance needs a patient and a version of the same clinic`() {
        val a = UUID.randomUUID()
        val b = UUID.randomUUID()
        val patientOfA = newPatient(a)
        val typeOfA = newType(a)
        val patientOfB = newPatient(b)
        val typeOfB = newType(b)
        assertThatThrownBy { accept(a, patientOfA, typeOfA, version = 2) }.rootCause().hasMessageContaining("violates foreign key constraint") // versão que não existe
        accept(a, patientOfA, typeOfA)
        assertThatThrownBy { accept(b, patientOfA, typeOfB) }.rootCause().hasMessageContaining("violates foreign key constraint") // paciente de outra clínica
        assertThatThrownBy { accept(b, patientOfB, typeOfA) }.rootCause().hasMessageContaining("violates foreign key constraint") // tipo de outra clínica
        accept(b, patientOfB, typeOfB)
    }

    @Test
    fun `only one acceptance is active per patient and type, and after a revocation a new one is another row`() {
        val clinic = UUID.randomUUID()
        val patient = newPatient(clinic)
        val type = newType(clinic)
        val first = accept(clinic, patient, type)
        assertThatThrownBy { accept(clinic, patient, type) }.rootCause().hasMessageContaining("consent_grant_one_active_key")
        inClinic(clinic) { jdbc.update("update registry.consent_grant set revoked_at = now(), revoked_by = ? where id = ?", UUID.randomUUID(), first) }
        accept(clinic, patient, type)
        assertThat(count(clinic, "select count(*) from registry.consent_grant where patient_id = ? and type_id = ?", patient, type)).isEqualTo(2)
        assertThat(count(clinic, "select count(*) from registry.consent_grant where patient_id = ? and type_id = ? and revoked_at is null", patient, type)).isEqualTo(1)
    }

    @Test
    fun `the application can only record the revocation, once, and never change the acceptance data`() {
        val clinic = UUID.randomUUID()
        val patient = newPatient(clinic)
        val type = newType(clinic)
        val grant = accept(clinic, patient, type)
        val by = UUID.randomUUID()

        listOf("accepted_by = gen_random_uuid()", "accepted_at = now()", "version_number = 1", "patient_id = patient_id", "type_id = type_id", "channel = channel").forEach {
            assertThatThrownBy { inClinic(clinic) { jdbc.update("update registry.consent_grant set $it where id = ?", grant) } }
                .describedAs(it).rootCause().hasMessageContaining("permission denied")
        }
        assertThatThrownBy { inClinic(clinic) { jdbc.update("update registry.consent_grant set revoked_at = now() where id = ?", grant) } }
            .rootCause().hasMessageContaining("consent_grant_revocation_pair")
        assertThatThrownBy { inClinic(clinic) { jdbc.update("update registry.consent_grant set revoked_at = now() - interval '1 day', revoked_by = ? where id = ?", by, grant) } }
            .rootCause().hasMessageContaining("consent_grant_revocation_order")

        inClinic(clinic) { jdbc.update("update registry.consent_grant set revoked_at = now(), revoked_by = ? where id = ?", by, grant) }
        assertThatThrownBy { inClinic(clinic) { jdbc.update("update registry.consent_grant set revoked_at = now(), revoked_by = ? where id = ?", UUID.randomUUID(), grant) } }
            .rootCause().hasMessageContaining("final")
        assertThatThrownBy { ownerSql(clinic) { it.executeUpdate("update registry.consent_grant set revoked_at = null, revoked_by = null where id = '$grant'") } }.hasMessageContaining("final")
        // um aceite ainda vigente: nem o dono altera os dados do aceite
        val live = accept(clinic, newPatient(clinic), type)
        assertThatThrownBy { ownerSql(clinic) { it.executeUpdate("update registry.consent_grant set accepted_by = gen_random_uuid() where id = '$live'") } }.hasMessageContaining("immutable")
        assertThatThrownBy { ownerSql(clinic) { it.executeUpdate("update registry.consent_grant set version_number = 1, accepted_at = now() where id = '$live'") } }.hasMessageContaining("immutable")
        assertThat(count(clinic, "select count(*) from registry.consent_grant where id = ? and revoked_by = ?", grant, by)).isEqualTo(1)
    }

    @Test
    fun `the application cannot delete an acceptance`() {
        val clinic = UUID.randomUUID()
        val grant = accept(clinic, newPatient(clinic), newType(clinic))
        assertThatThrownBy { inClinic(clinic) { jdbc.update("delete from registry.consent_grant where id = ?", grant) } }.rootCause().hasMessageContaining("permission denied")
        assertThat(count(clinic, "select count(*) from registry.consent_grant where id = ?", grant)).isEqualTo(1)
    }

    @Test
    fun `the platform scope does not open acceptances, types or versions`() {
        val clinic = UUID.randomUUID()
        accept(clinic, newPatient(clinic), newType(clinic))
        listOf("registry.consent_grant", "registry.consent_type", "registry.consent_type_version").forEach { table ->
            val rows = TenantScope.runAsPlatform { tx.execute { jdbc.queryForObject("select count(*) from $table", Int::class.java) }!! }
            assertThat(rows).describedAs(table).isZero()
        }
    }

    // --- consent_template ---

    private fun insertTemplate(name: String, active: Boolean = true) = jdbc.update(
        "insert into registry.consent_template (id, name, text, required_on_signup, controls_messaging, active) values (?, ?, 'Texto do modelo', true, false, ?)",
        UUID.randomUUID(), name, active,
    )

    @Test
    fun `every clinic and even no context reads the templates, but only the platform scope writes them`() {
        val name = "Modelo ${UUID.randomUUID()}"
        asPlatform { insertTemplate(name) }

        val clinicA = UUID.randomUUID()
        val clinicB = UUID.randomUUID()
        listOf(clinicA, clinicB).forEach { clinic ->
            assertThat(count(clinic, "select count(*) from registry.consent_template where name = ?", name)).isEqualTo(1)
        }
        assertThat(tx.execute { jdbc.queryForObject("select count(*) from registry.consent_template where name = ?", Int::class.java, name) }).isEqualTo(1)

        assertThatThrownBy { inClinic(clinicA) { insertTemplate("Invasor ${UUID.randomUUID()}") } }.rootCause().hasMessageContaining("row-level security")
        assertThatThrownBy { tx.executeWithoutResult { insertTemplate("Sem contexto ${UUID.randomUUID()}") } }.rootCause().hasMessageContaining("row-level security")

        val changedByClinic = TenantScope.runAs(clinicA) { tx.execute { jdbc.update("update registry.consent_template set text = 'Alterado' where name = ?", name) }!! }
        assertThat(changedByClinic).isZero()
        assertThat(count(clinicA, "select count(*) from registry.consent_template where name = ? and text = 'Texto do modelo'", name)).isEqualTo(1)

        asPlatform { jdbc.update("update registry.consent_template set text = 'Texto revisado' where name = ?", name) }
        assertThat(count(clinicB, "select count(*) from registry.consent_template where name = ? and text = 'Texto revisado'", name)).isEqualTo(1)
    }

    @Test
    fun `templates have a unique name and are never deleted`() {
        val name = "Modelo único ${UUID.randomUUID()}"
        asPlatform { insertTemplate(name) }
        assertThatThrownBy { asPlatform { insertTemplate(name.uppercase()) } }.rootCause().hasMessageContaining("consent_template_name_key")
        assertThatThrownBy { asPlatform { jdbc.update("delete from registry.consent_template where name = ?", name) } }.rootCause().hasMessageContaining("permission denied")
        assertThatThrownBy { asPlatform { jdbc.update("insert into registry.consent_template (id, name, text, required_on_signup, controls_messaging) values (?, ' ', 'x', true, true)", UUID.randomUUID()) } }
            .rootCause().hasMessageContaining("violates check constraint")
    }
}
