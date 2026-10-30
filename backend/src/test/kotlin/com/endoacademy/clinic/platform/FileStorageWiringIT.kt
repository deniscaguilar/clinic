package com.endoacademy.clinic.platform

import com.endoacademy.clinic.platform.adapter.out.storage.InMemoryFileStorage
import com.endoacademy.clinic.platform.api.FileStorage
import com.endoacademy.clinic.support.DatabaseIT
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired

class FileStorageWiringIT : DatabaseIT() {

    @Autowired
    lateinit var fileStorage: FileStorage

    @Test
    fun `the memory provider wires the in-memory file storage`() {
        assertThat(fileStorage).isInstanceOf(InMemoryFileStorage::class.java)
    }
}
