package com.endoacademy.clinic.registry.application

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.AuditEvent
import com.endoacademy.clinic.platform.api.AuditTrail
import com.endoacademy.clinic.platform.api.Authorizer
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.registry.api.ConsentAccepted
import com.endoacademy.clinic.registry.api.ConsentRevoked
import com.endoacademy.clinic.registry.domain.ConsentGrant
import java.time.Clock
import java.util.UUID

/** Aceites de consentimento da clínica ativa. Roda na transação do [Transactions]; a RLS limita tudo ao tenant. */
interface ConsentGrantStore {
    fun find(patientId: UUID, id: UUID): ConsentGrant?

    /** Do mais recente para o mais antigo. */
    fun listByPatient(patientId: UUID): List<ConsentGrant>

    /** Existe aceite vigente (não revogado) deste tipo para o paciente? */
    fun hasActive(patientId: UUID, typeId: UUID): Boolean

    /** Lança `ApiException` 409 `CONSENT_ALREADY_ACCEPTED` se uma corrida criou outro aceite vigente do mesmo tipo. */
    fun insert(clinicId: UUID, grant: ConsentGrant)

    /** Grava só a revogação. Lança 409 `CONSENT_ALREADY_REVOKED` se outra requisição revogou antes. */
    fun revoke(grant: ConsentGrant)
}

/** Publica um evento na transação em curso (adaptador: registro de eventos do Spring Modulith). */
fun interface EventPublisher {
    fun publish(event: Any)
}

/** Aceite com o nome do tipo, para a tela. */
data class ConsentGrantView(val grant: ConsentGrant, val typeName: String)

private const val RECORD_CONSENT = "RECORD_CONSENT"
private const val VIEW_PATIENTS = "VIEW_PATIENTS"

private fun patientNotFound() = ApiException(ErrorKind.NOT_FOUND, "PATIENT_NOT_FOUND")

/** Registra o aceite de um tipo por um paciente (RF12), ligado à versão vigente, e publica `ConsentAccepted`. */
class AcceptConsent(
    private val authorizer: Authorizer,
    private val patients: PatientStore,
    private val types: ConsentTypeStore,
    private val grants: ConsentGrantStore,
    private val events: EventPublisher,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val clock: Clock,
    private val newId: () -> UUID = UUID::randomUUID,
) {
    fun accept(user: CurrentUser, patientId: UUID, typeId: UUID): ConsentGrant {
        authorizer.require(user, RECORD_CONSENT)
        return transactions.inTransaction {
            patients.find(patientId) ?: throw patientNotFound()
            val type = types.find(typeId) ?: throw ApiException(ErrorKind.NOT_FOUND, "CONSENT_TYPE_NOT_FOUND")
            if (grants.hasActive(patientId, typeId)) throw ApiException(ErrorKind.CONFLICT, "CONSENT_ALREADY_ACCEPTED")
            val grant = domain { ConsentGrant.accept(newId(), patientId, type, user.userId, clock.instant()) }
            grants.insert(user.clinicId, grant)
            events.publish(ConsentAccepted(user.clinicId, patientId, grant.id))
            auditTrail.record(user, AuditEvent("ACCEPT_CONSENT", "CONSENT_GRANT", grant.id.toString()))
            grant
        }
    }
}

/** Revoga um aceite (RF12): registra quem e quando, não apaga, e publica `ConsentRevoked`. */
class RevokeConsent(
    private val authorizer: Authorizer,
    private val grants: ConsentGrantStore,
    private val events: EventPublisher,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
    private val clock: Clock,
) {
    fun revoke(user: CurrentUser, patientId: UUID, grantId: UUID): ConsentGrant {
        authorizer.require(user, RECORD_CONSENT)
        return transactions.inTransaction {
            val grant = grants.find(patientId, grantId) ?: throw ApiException(ErrorKind.NOT_FOUND, "CONSENT_NOT_FOUND")
            val revoked = domain { grant.revoke(user.userId, clock.instant()) }
            grants.revoke(revoked)
            events.publish(ConsentRevoked(user.clinicId, patientId, grantId))
            auditTrail.record(user, AuditEvent("REVOKE_CONSENT", "CONSENT_GRANT", grantId.toString()))
            revoked
        }
    }
}

/** Os aceites de um paciente. Ler consentimento é ler dado do paciente: uma leitura = um evento de auditoria. */
class ListPatientConsents(
    private val authorizer: Authorizer,
    private val patients: PatientStore,
    private val types: ConsentTypeStore,
    private val grants: ConsentGrantStore,
    private val auditTrail: AuditTrail,
    private val transactions: Transactions,
) {
    fun list(user: CurrentUser, patientId: UUID): List<ConsentGrantView> {
        if (!user.has(RECORD_CONSENT) && !user.has(VIEW_PATIENTS)) authorizer.require(user, VIEW_PATIENTS)
        return transactions.inTransaction {
            patients.find(patientId) ?: throw patientNotFound()
            val names = types.list(null).associate { it.id to it.name }
            val views = grants.listByPatient(patientId).map { ConsentGrantView(it, names[it.typeId].orEmpty()) }
            auditTrail.record(user, AuditEvent("LIST_PATIENT_CONSENTS", "PATIENT", patientId.toString(), details = setOf("count.${views.size}")))
            views
        }
    }
}
