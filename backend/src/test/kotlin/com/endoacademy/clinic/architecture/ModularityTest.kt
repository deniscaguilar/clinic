package com.endoacademy.clinic.architecture

import com.endoacademy.clinic.ClinicApplication
import org.junit.jupiter.api.Test
import org.springframework.modulith.core.ApplicationModules

class ModularityTest {
    @Test
    fun `module structure has no cycles and uses only named interfaces`() {
        ApplicationModules.of(ClinicApplication::class.java).verify()
    }
}
