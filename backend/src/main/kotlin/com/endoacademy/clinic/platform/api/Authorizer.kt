package com.endoacademy.clinic.platform.api

/** Checagem de permissão feita nos casos de uso (camada `application`), nunca no controller. */
interface Authorizer {
    /** Lança [PermissionDeniedException] (403) e audita `DENIED` se o usuário não tem a permissão. */
    fun require(user: CurrentUser, permission: String)
}

class PermissionDeniedException(val permission: String) : ApiException(ErrorKind.FORBIDDEN, "PERMISSION_DENIED")
