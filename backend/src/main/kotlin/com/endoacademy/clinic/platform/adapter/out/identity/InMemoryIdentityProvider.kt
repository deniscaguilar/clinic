package com.endoacademy.clinic.platform.adapter.out.identity

import com.endoacademy.clinic.platform.application.IdentityProvider
import com.endoacademy.clinic.platform.application.IdentityProviderException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicReference

/**
 * Adaptador falso, em memória, para o desenvolvimento local e os testes. O `sub` é determinístico a partir do e-mail,
 * então o mesmo e-mail gera o mesmo `sub` em qualquer execução. O e-mail [LOCAL_EMAIL] recebe o `sub` que o
 * `mock-oauth2-server` emite (`docker/oidc/oidc-config.json`), para o login local casar com o usuário criado.
 */
class InMemoryIdentityProvider : IdentityProvider {

    private val subsByEmail = ConcurrentHashMap<String, String>()
    private val resent = CopyOnWriteArrayList<String>()
    private val disabled = CopyOnWriteArrayList<String>()
    private val pendingFailure = AtomicReference<IdentityProviderException?>()

    override fun createUser(email: String, name: String): String {
        throwPendingFailure()
        val normalized = normalize(email)
        return subsByEmail.computeIfAbsent(normalized) { subFor(it) }
    }

    override fun resendInvitation(email: String) {
        throwPendingFailure()
        resent += normalize(email)
    }

    override fun disableUser(sub: String) {
        throwPendingFailure()
        disabled += sub
    }

    /** A próxima chamada a qualquer operação lança [failure]; depois o provedor volta a funcionar. */
    fun failNext(failure: IdentityProviderException) = pendingFailure.set(failure)

    fun users(): Set<String> = subsByEmail.keys.toSet()

    fun resent(): List<String> = resent.toList()

    fun disabled(): List<String> = disabled.toList()

    private fun throwPendingFailure() {
        pendingFailure.getAndSet(null)?.let { throw it }
    }

    private fun normalize(email: String) = email.trim().lowercase()

    private fun subFor(email: String) =
        if (email == LOCAL_EMAIL) LOCAL_SUB else UUID.nameUUIDFromBytes(email.toByteArray()).toString()

    companion object {
        const val LOCAL_EMAIL = "local-user@example.test"
        const val LOCAL_SUB = "local-user"
    }
}
