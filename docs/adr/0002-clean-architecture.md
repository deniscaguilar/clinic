# ADR-0002: Clean Architecture dentro de cada módulo

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

Queremos regras de negócio testáveis sem framework, serviços externos substituíveis (Cognito, S3, LLM, Endo AI) e execução 100% local. Também precisamos de padrão uniforme que a IA reproduza com segurança.

## Decisão

Cada módulo segue camadas `api` / `domain` / `application` / `adapter/in` / `adapter/out` / `config`, com dependência apontando para dentro (`domain` ← `application` ← `adapter`).

- O domínio não importa Spring, JPA, Jackson nem SDKs da AWS.
- Entidade de domínio e entidade JPA são separadas, com mapeamento explícito.
- Todo serviço externo é um **port** com adaptador real e adaptador local/falso.
- Estrutura **idêntica** em todos os módulos; módulos CRUD podem ter domínio fino.
- Regras verificadas por ArchUnit no CI.

## Consequências

- Mais código de mapeamento e interfaces; compensado pela IA e pela testabilidade (unitários rápidos, sem banco).
- Trocar provedor (Cognito → outro OIDC) é trocar adaptador.
