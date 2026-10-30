package com.endoacademy.clinic.platform.application

/**
 * Port de saída para o provedor de identidade (Cognito em produção, falso em memória no local e nos testes).
 * O provedor só autentica; perfis e vínculos ficam no banco da aplicação (ADR-0004).
 */
interface IdentityProvider {
    /**
     * Cria o login e dispara o convite por e-mail. Se o login já existe no provedor, devolve o `sub` existente
     * sem novo convite. O e-mail é normalizado (minúsculas, sem espaços nas pontas).
     */
    fun createUser(email: String, name: String): String

    /** Reenvia o convite de um login ainda não confirmado. */
    fun resendInvitation(email: String)

    /** Desativa o login pelo `sub`. */
    fun disableUser(sub: String)
}

/** Falha do provedor. A mensagem nunca carrega e-mail nem nome (dado pessoal não vai para log). */
class IdentityProviderException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
