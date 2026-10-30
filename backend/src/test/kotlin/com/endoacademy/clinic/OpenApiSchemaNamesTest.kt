package com.endoacademy.clinic

import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * O springdoc nomeia cada esquema pelo nome simples da classe e, havendo dois com o mesmo nome, funde os dois em um
 * (o contrato e o cliente gerado do front ficam errados sem aviso). Por isso o nome simples de cada DTO aninhado em
 * controller tem que ser único em toda a aplicação.
 */
class OpenApiSchemaNamesTest {

    @Test
    fun `nested DTOs of controllers have unique simple names`() {
        val classes = ClassFileImporter().withImportOption(ImportOption.DoNotIncludeTests()).importPackages("com.endoacademy.clinic")
        val nested = classes.filter { it.isNestedClass && it.enclosingClass.get().simpleName.endsWith("Controller") }

        val duplicates = nested.groupBy { it.simpleName }.filterValues { it.size > 1 }
            .mapValues { (_, group) -> group.map { it.enclosingClass.get().simpleName }.sorted() }

        assertThat(nested).describedAs("o teste precisa enxergar os DTOs dos controllers").isNotEmpty()
        assertThat(duplicates).describedAs("nome de esquema OpenAPI repetido (renomeie com o prefixo do recurso)").isEmpty()
    }
}
