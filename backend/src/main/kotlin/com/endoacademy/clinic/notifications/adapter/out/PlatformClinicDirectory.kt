package com.endoacademy.clinic.notifications.adapter.out

import com.endoacademy.clinic.notifications.application.ClinicDirectory
import com.endoacademy.clinic.platform.api.ActiveClinics
import java.util.UUID

/** As clínicas ativas pela `platform.api`: só ids. */
class PlatformClinicDirectory(private val activeClinics: ActiveClinics) : ClinicDirectory {
    override fun activeClinicIds(): List<UUID> = activeClinics.ids()
}
