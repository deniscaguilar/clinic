package com.endoacademy.clinic.registry.adapter.out.persistence

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.application.PatientStore
import com.endoacademy.clinic.registry.domain.Address
import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Email
import com.endoacademy.clinic.registry.domain.Patient
import com.endoacademy.clinic.registry.domain.Phone
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

/**
 * Pacientes da clínica ativa, com mapeamento explícito entre o domínio e as colunas. A RLS por tenant limita todas as
 * consultas. Dado pessoal: nada daqui vai para log, e a mensagem de erro do banco (que cita o valor) nunca sobe.
 */
class JdbcPatientStore(private val jdbc: JdbcTemplate) : PatientStore {

    override fun find(id: UUID): Patient? = jdbc.query("$SELECT where id = ?", { rs, _ -> rs.toPatient() }, id).firstOrNull()

    override fun findByCpf(cpf: Cpf): Patient? = jdbc.query("$SELECT where cpf = ?", { rs, _ -> rs.toPatient() }, cpf.value).firstOrNull()

    override fun cpfTaken(cpf: Cpf, exceptId: UUID?): Boolean =
        jdbc.queryForObject(
            "select exists (select 1 from registry.patient where cpf = ? and (cast(? as uuid) is null or id <> cast(? as uuid)))",
            Boolean::class.java,
            cpf.value,
            exceptId,
            exceptId,
        ) == true

    override fun insert(clinicId: UUID, patient: Patient) {
        translatingDuplicates {
            jdbc.update(
                """insert into registry.patient
                   (id, tenant_id, full_name, cpf, birth_date, whatsapp, email, landline,
                    zip_code, street, street_number, complement, district, city, state)
                   values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""",
                patient.id,
                clinicId,
                patient.fullName,
                patient.cpf.value,
                patient.birthDate,
                patient.whatsapp?.value,
                patient.email?.value,
                patient.landline?.value,
                patient.address.zipCode,
                patient.address.street,
                patient.address.number,
                patient.address.complement,
                patient.address.district,
                patient.address.city,
                patient.address.state,
            )
        }
    }

    override fun update(patient: Patient) {
        translatingDuplicates {
            jdbc.update(
                """update registry.patient set full_name = ?, cpf = ?, birth_date = ?, whatsapp = ?, email = ?, landline = ?,
                   zip_code = ?, street = ?, street_number = ?, complement = ?, district = ?, city = ?, state = ?, updated_at = now()
                   where id = ?""",
                patient.fullName,
                patient.cpf.value,
                patient.birthDate,
                patient.whatsapp?.value,
                patient.email?.value,
                patient.landline?.value,
                patient.address.zipCode,
                patient.address.street,
                patient.address.number,
                patient.address.complement,
                patient.address.district,
                patient.address.city,
                patient.address.state,
                patient.id,
            )
        }
    }

    override fun list(nameContains: String?, offset: Int, limit: Int): List<Patient> =
        jdbc.query(
            "$SELECT where (cast(? as text) is null or position(lower(cast(? as text)) in lower(full_name)) > 0) order by lower(full_name), id offset ? limit ?",
            { rs, _ -> rs.toPatient() },
            nameContains,
            nameContains,
            offset,
            limit,
        )

    override fun count(nameContains: String?): Long =
        jdbc.queryForObject(
            "select count(*) from registry.patient where (cast(? as text) is null or position(lower(cast(? as text)) in lower(full_name)) > 0)",
            Long::class.java,
            nameContains,
            nameContains,
        ) ?: 0L

    private fun ResultSet.toPatient() = Patient.restore(
        id = getObject("id", UUID::class.java),
        fullName = getString("full_name"),
        cpf = Cpf.parse(getString("cpf")),
        birthDate = getObject("birth_date", LocalDate::class.java),
        whatsapp = getString("whatsapp")?.let { Phone.parse(it) },
        email = getString("email")?.let { Email.parse(it) },
        landline = getString("landline")?.let { Phone.parse(it) },
        address = Address.of(
            getString("zip_code"),
            getString("street"),
            getString("street_number"),
            getString("complement"),
            getString("district"),
            getString("city"),
            getString("state"),
        ),
    )

    /** Corrida entre dois cadastros com o mesmo CPF: o índice único decide e vira 409, sem citar o valor. */
    private fun translatingDuplicates(block: () -> Unit) = try {
        block()
    } catch (_: DuplicateKeyException) {
        throw ApiException(ErrorKind.CONFLICT, "CPF_ALREADY_REGISTERED")
    }

    private companion object {
        const val SELECT = """select id, full_name, cpf, birth_date, whatsapp, email, landline,
            zip_code, street, street_number, complement, district, city, state from registry.patient"""
    }
}
