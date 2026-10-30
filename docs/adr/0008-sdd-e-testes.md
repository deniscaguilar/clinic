# ADR-0008: Spec-Driven Development e estratégia de testes

- **Status:** Aceita
- **Data:** 2026-10-26

## Decisão

**SDD desde o início.** A spec é a fonte da verdade; código vem depois e acompanha a spec. Fluxo por funcionalidade em `specs/<modulo>/<feature>/`: `spec.md` (aprovada por humano) → `plan.md` (aprovado) → `tasks.md` → implementação test-first. Regras operacionais no `CLAUDE.md`.

**Testes**
- Unitários (`*Test`) em `domain` e `application`, com fakes dos ports; meta ≥ 85% de cobertura (linhas e branches) nessas camadas, com portão no CI.
- Integração (`*IT`) com Testcontainers (Postgres, LocalStack), Flyway real, API completa, isolamento RLS e `@ApplicationModuleTest`.
- Arquitetura com ArchUnit e Modulith.
- Front com Vitest e Testing Library; Playwright nos fluxos críticos.
- Tudo roda localmente com `./mvnw verify`.

**Paridade local/produção:** mesma imagem, variáveis de ambiente diferentes, adaptadores locais para serviços AWS e provedores externos. O que o local não prova (Cognito real, IAM, rede, DNS/TLS, push em iOS) é coberto por um ambiente de fumaça efêmero na AWS.

## Ferramenta

Estrutura de specs própria e leve (templates em `specs/_templates/`). GitHub Spec Kit pode ser avaliado depois.
