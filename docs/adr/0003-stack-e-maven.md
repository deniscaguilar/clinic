# ADR-0003: Stack base e Maven

- **Status:** Aceita
- **Data:** 2026-10-26

## Decisão

- **Kotlin** (2.4.x) como linguagem principal; Java só com motivo registrado. `jvmTarget` 25.
- **JDK 25 (LTS)**, Eclipse Temurin, imagens arm64.
- **Spring Boot 4.1.x** e **Spring Modulith 2.1.x** (confirmar compatibilidade pelo BOM ao gerar o projeto).
- **Maven** com wrapper, um único módulo de build.

## Por que Maven e não Gradle

- O desenvolvedor já domina Maven; o custo de aprender Gradle não se paga neste projeto.
- Um único módulo de build não usa as forças do Gradle (multi-projeto, cache incremental).
- Kotlin + Java no mesmo módulo funciona com o `kotlin-maven-plugin` (Kotlin compila antes do Java).
- `mvn verify` separa nativamente unitários (Surefire, `*Test`) e integração (Failsafe, `*IT`).
- Spring Boot, JaCoCo e Testcontainers têm o mesmo suporte nos dois.

## Gatilhos para reavaliar

Build lento (vários minutos) ou decisão de separar módulos em projetos de build distintos.

## Versões verificadas em 26/10/2026

Spring Boot 4.1.2, Kotlin 2.4.21, Spring Modulith 2.1.2, Testcontainers 2.0.5, Flyway 13.11.1, MockK 1.14.11, OWASP dependency-check 13.0.0. Reconferir ao criar o `pom.xml`.

**Ajuste em 28/10/2026 (T1.1):** o BOM do Boot 4.1.2 gerencia Kotlin 2.3.21 e Flyway 12.4.0. Decisão do Denis: **seguir o BOM** (CLAUDE.md, seção 7), sem sobrescrever; Kotlin e Flyway valem o que o BOM trouxer. O Testcontainers 2.0.5 já coincide com o BOM; Modulith 2.1.2, MockK 1.14.11 e dependency-check 13.0.0 ficam como listados.
