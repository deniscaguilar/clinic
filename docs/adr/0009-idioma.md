# ADR-0009: Idioma da documentação e do código

- **Status:** Aceita
- **Data:** 2026-10-26

## Contexto

As especificações são lidas e aprovadas por pessoas de negócio em português, e o histórico de commits e branches precisa ser compreensível para qualquer desenvolvedor que entre no projeto. Já o código, as ferramentas e a IA funcionam melhor com identificadores em um único idioma, e inglês evita mistura de idiomas e problemas de acentuação.

## Decisão

- **Português:** specs, ADRs, roadmap, README e demais documentação, **mensagens de commit** (descrição) e **nomes de branch**.
  - Commits seguem o padrão convencional com o tipo em inglês e a descrição em português: `feat: cria cadastro de clínicas`.
  - Branches sem acentos: `feat/m0-tenancy-por-clinica`.
- **Inglês:** todo o código e a estrutura do projeto: pastas e nomes de arquivos, pacotes (raiz provisória `com.endoacademy.clinic`), classes, funções, variáveis, nomes de testes, comentários, tabelas e colunas, migrações, endpoints e OpenAPI, enums, logs, códigos de erro, módulos Maven, recursos de infra e containers.
- **Interface do usuário:** textos e mensagens exibidas em **pt-BR via i18n**, com chaves em inglês.
- **Glossário** português → inglês em `CLAUDE.md` (seção 12). Termo novo entra no glossário antes de ser usado no código.

## Consequências

Pequeno custo de tradução entre spec e código, compensado pelo glossário único. Histórico de commits legível para quem lê em português.
