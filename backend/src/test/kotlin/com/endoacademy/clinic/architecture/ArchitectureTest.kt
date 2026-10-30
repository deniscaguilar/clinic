package com.endoacademy.clinic.architecture

import com.tngtech.archunit.base.DescribedPredicate
import com.tngtech.archunit.core.domain.JavaClass
import com.tngtech.archunit.core.importer.ClassFileImporter
import com.tngtech.archunit.core.importer.ImportOption
import com.tngtech.archunit.lang.ArchRule
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses
import org.junit.jupiter.api.Test

/**
 * Regras do CLAUDE.md, seção 3, e mapa de dependências de docs/architecture.md, seção 3.
 * Classes de teste ficam fora da análise.
 */
class ArchitectureTest {
    private val base = "com.endoacademy.clinic"

    private val classes = ClassFileImporter()
        .withImportOption(ImportOption.DoNotIncludeTests())
        .importPackages(base)

    /** Módulo -> módulos de que pode depender (somente pela `api`). Fonte: docs/architecture.md. */
    private val allowed: Map<String, Set<String>> = mapOf(
        "platform" to setOf(),
        "registry" to setOf("platform"),
        "notifications" to setOf("platform", "registry"),
        "scheduling" to setOf("platform", "registry"),
        "aigateway" to setOf("platform"),
        "inventory" to setOf("platform", "registry", "finance", "notifications"),
        "finance" to setOf("platform", "registry"),
        "reports" to setOf("platform", "registry"),
        "endoai" to setOf("platform", "registry", "aigateway"),
        "dashboard" to setOf("platform", "inventory", "finance", "reports", "scheduling"),
        "patient360" to setOf("platform", "registry", "endoai", "aigateway"),
        "simulator" to setOf("platform", "finance", "aigateway"),
        "assistant" to setOf("platform", "dashboard", "patient360", "aigateway"),
    )

    private fun check(rule: ArchRule) = rule.allowEmptyShould(true).check(classes)

    @Test
    fun `domain does not use frameworks`() = check(
        noClasses().that().resideInAPackage("$base.*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "org.springframework..",
                "jakarta..",
                "org.hibernate..",
                "com.fasterxml.jackson..",
                "tools.jackson..",
                "software.amazon.awssdk..",
            )
            .because("o domínio não importa Spring, JPA, Jackson nem AWS (regra 1)"),
    )

    @Test
    fun `domain does not depend on outer layers`() = check(
        noClasses().that().resideInAPackage("$base.*.domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "$base.*.application..",
                "$base.*.adapter..",
                "$base.*.config..",
            ),
    )

    @Test
    fun `application does not depend on adapters or config`() = check(
        noClasses().that().resideInAPackage("$base.*.application..")
            .should().dependOnClassesThat().resideInAnyPackage(
                "$base.*.adapter..",
                "$base.*.config..",
            ),
    )

    @Test
    fun `domain entities are not JPA entities`() = check(
        noClasses().that().resideInAPackage("$base.*.domain..")
            .should().beAnnotatedWith("jakarta.persistence.Entity")
            .because("entidade de domínio e entidade JPA são classes distintas (regra 2)"),
    )

    @Test
    fun `other modules use only the api package`() {
        allowed.keys.forEach { module ->
            check(
                noClasses().that().resideOutsideOfPackage("$base.$module..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                        "$base.$module.domain..",
                        "$base.$module.application..",
                        "$base.$module.adapter..",
                        "$base.$module.config..",
                    )
                    .because("um módulo só usa outro pela api (regra 3): $module"),
            )
        }
    }

    @Test
    fun `modules depend only on the modules allowed by the dependency map`() {
        allowed.forEach { (module, permitted) ->
            val forbidden = allowed.keys.filter { it != module && it !in permitted }
            if (forbidden.isEmpty()) return@forEach
            check(
                noClasses().that().resideInAPackage("$base.$module..")
                    .should().dependOnClassesThat().resideInAnyPackage(*forbidden.map { "$base.$it.." }.toTypedArray())
                    .because("docs/architecture.md, seção 3: $module só pode depender de $permitted"),
            )
        }
    }

    @Test
    fun `only declared modules exist under the base package`() = check(
        classes().that().resideInAPackage("$base..").and(inAModulePackage())
            .should(object : com.tngtech.archunit.lang.ArchCondition<JavaClass>("be in a declared module") {
                override fun check(item: JavaClass, events: com.tngtech.archunit.lang.ConditionEvents) {
                    val module = item.packageName.removePrefix("$base.").substringBefore('.')
                    if (module !in allowed) {
                        events.add(
                            com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                item,
                                "${item.name} está no pacote '$module', que não é um módulo declarado " +
                                    "(sem core, common ou shared; CLAUDE.md, seção 3)",
                            ),
                        )
                    }
                }
            }),
    )

    @Test
    fun `persistence tables use the schema of their own module`() = check(
        classes().that().areAnnotatedWith("jakarta.persistence.Table").and(inAModulePackage())
            .should(object : com.tngtech.archunit.lang.ArchCondition<JavaClass>("use the module schema in @Table") {
                override fun check(item: JavaClass, events: com.tngtech.archunit.lang.ConditionEvents) {
                    val module = item.packageName.removePrefix("$base.").substringBefore('.')
                    val schema = item.getAnnotationOfType("jakarta.persistence.Table").get("schema").orElse("") as String
                    if (schema != module) {
                        events.add(
                            com.tngtech.archunit.lang.SimpleConditionEvent.violated(
                                item,
                                "${item.name} usa o schema '$schema', mas pertence ao módulo '$module'",
                            ),
                        )
                    }
                }
            }),
    )

    private fun inAModulePackage() = object : DescribedPredicate<JavaClass>("in a module package") {
        override fun test(input: JavaClass) = input.packageName.startsWith("$base.")
    }
}
