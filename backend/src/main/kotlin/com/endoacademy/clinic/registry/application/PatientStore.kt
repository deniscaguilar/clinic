package com.endoacademy.clinic.registry.application

import com.endoacademy.clinic.registry.domain.Cpf
import com.endoacademy.clinic.registry.domain.Patient
import java.util.UUID

/**
 * Pacientes da clínica ativa. Roda na transação do [Transactions]; a RLS limita tudo ao tenant. Dado pessoal: nenhuma
 * implementação registra CPF, nome ou contato em log nem em mensagem de erro.
 */
interface PatientStore {
    fun find(id: UUID): Patient?

    fun findByCpf(cpf: Cpf): Patient?

    /** O CPF já é de outro paciente da clínica? `exceptId` é o próprio paciente, na atualização. */
    fun cpfTaken(cpf: Cpf, exceptId: UUID?): Boolean

    /** Lança `ApiException` 409 `CPF_ALREADY_REGISTERED` se o CPF já existe na clínica (corrida decidida pelo banco). */
    fun insert(clinicId: UUID, patient: Patient)

    /** Grava todos os campos, inclusive o CPF; mesma tradução de duplicata do [insert]. */
    fun update(patient: Patient)

    /** Ordenados pelo nome sem diferenciar maiúsculas; `nameContains` filtra por trecho do nome. */
    fun list(nameContains: String?, offset: Int, limit: Int): List<Patient>

    fun count(nameContains: String?): Long
}
