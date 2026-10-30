# ADR-0001: Monolito modular

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Desenvolvedor único com assistente de IA, modelo de dados ainda em descoberta, multi-clínica desde o início e piloto com uma clínica. Microsserviços trariam custo operacional (rede, contratos versionados, tracing distribuído, vários pipelines) sem resolver um problema que já exista.

## Decisão

Um **monolito modular**: um artefato, módulos por contexto de negócio, fronteiras verificadas por **Spring Modulith** (`verify()`) e **ArchUnit**. O mesmo artefato roda com perfis `api` e `worker`; no piloto, ambos juntos (`all`).

- Módulos conversam só pela `api` pública ou por eventos de domínio (registro de eventos persistido em JDBC, que funciona como outbox).
- Um Postgres, **um schema por módulo**, sem joins nem FKs entre schemas.
- "Deploy independente por módulo" **não** é objetivo agora. A independência necessária é de código (fronteiras) e de infraestrutura (stacks).
- Módulos por **contexto de negócio**, com nome de uma palavra: `platform`, `registry`, `scheduling`, `inventory`, `finance`, `reports`, `dashboard`, `notifications`, `endoai`, `patient360`, `aigateway` (depois `simulator` e `assistant`). Pacote raiz provisório `com.endoacademy.clinic.<module>`.
- Canais (como o backoffice `/admin`) **não são módulos**; vivem no adaptador web do módulo dono da regra. Sem módulos `core`, `common` ou `shared`.
- Mapa de dependências permitidas em `docs/architecture.md`.

## Consequências

- Operação simples, um pipeline, depuração local fácil, módulos pequenos que cabem no contexto da IA.
- Disciplina de fronteiras precisa ser cobrada no CI, senão vira monolito comum.
- Extrair um módulo em serviço fica possível porque os schemas e as `api`s já são separados.

## Gatilhos para extrair um serviço

Escala distinta, ciclo de release distinto, tecnologia distinta (por exemplo, Python para ML) ou isolamento de falha. Candidatos: ingestão do Endo AI e assistente de IA.
