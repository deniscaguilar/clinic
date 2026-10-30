package com.endoacademy.clinic.platform.adapter.`in`.web

import com.endoacademy.clinic.platform.api.ApiException
import com.endoacademy.clinic.platform.api.CurrentUser
import com.endoacademy.clinic.platform.api.ErrorKind
import com.endoacademy.clinic.platform.application.GetMyProfile
import com.endoacademy.clinic.platform.application.ListMyClinics
import com.endoacademy.clinic.platform.application.ResolveSession
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/** Sessão (plano, seção 6): `/me` e `/me/clinics` não exigem clínica ativa; `/session` exige `X-Clinic-Id`. */
@RestController
@RequestMapping("/api/v1")
class SessionController(
    private val getMyProfile: GetMyProfile,
    private val listMyClinics: ListMyClinics,
    private val resolveSession: ResolveSession,
) {
    data class MeResponse(val id: UUID, val email: String, val name: String)

    data class ClinicResponse(val id: UUID, val name: String)

    data class SessionResponse(
        val user: MeResponse,
        val clinic: ClinicResponse,
        val roles: List<String>,
        val permissions: List<String>,
        val branding: BrandingController.BrandingResponse,
    )

    private fun subjectOf(jwt: Jwt): String = jwt.subject ?: throw ApiException(ErrorKind.UNAUTHENTICATED, "TOKEN_WITHOUT_SUBJECT")

    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): MeResponse =
        getMyProfile.get(subjectOf(jwt)).let { MeResponse(it.id, it.email, it.name) }

    @GetMapping("/me/clinics")
    fun clinics(@AuthenticationPrincipal jwt: Jwt): List<ClinicResponse> =
        listMyClinics.list(subjectOf(jwt)).map { ClinicResponse(it.id, it.name) }

    @GetMapping("/session")
    fun session(request: HttpServletRequest): SessionResponse {
        val session = resolveSession.resolve(request.getAttribute(ClinicContextFilter.CURRENT_USER) as CurrentUser)
        return SessionResponse(
            user = MeResponse(session.user.id, session.user.email, session.user.name),
            clinic = ClinicResponse(session.clinic.id, session.clinic.name),
            roles = session.roles,
            permissions = session.permissions.toList(),
            branding = BrandingController.BrandingResponse.of(session.branding),
        )
    }
}
