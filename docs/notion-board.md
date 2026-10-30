# Board no Notion

Espelho do `specs/platform/m0-foundation/tasks.md` (que é a fonte da verdade; em conflito, vale o `tasks.md`). Nenhum dado de paciente vai para o Notion.

- **Área:** página privada "Projeto Endo Academy" (`407058f8-4000-816e-a699-cc93f0a60e45`). O conector do Notion não cria teamspace e a conta não tem nenhum; compartilhar com o DCA é decisão do Denis.
- **Banco:** "Sistema da Clínica · Tarefas" (`cd0b4604bef44ae4977add61800c6665`); data source `collection://c17e7ff4-560b-47bc-aa51-b0c46d2e5042`.
- **Visões:** "Board por status" (agrupado por Status, ordenado por Ordem; `view://407058f8-4000-812c-8459-000caada9d81`) e a tabela geral.
- **Também na área:** a ata da reunião de 21/10 ("Self Clinic + Tecnologia + IA", `400058f8-4000-8078-b94a-f43e03825732`).
- **Propriedades:** Tarefa, ID (`T1.1`...), Status (`OPEN`, `DOING`, `REVIEW`, `BLOCKED`, `CANCELED`, `DONE`), Fatia (`S1` a `S6`), Critérios, Motivo do bloqueio, Ordem (1 a 62, sequência do `tasks.md`).
- **Conector:** `plugin:marketing:notion`; se pedir login, autenticar de novo em `/mcp`.

## Como atualizar

`notion-update-page` com `command: update_properties`, `page_id` do card e `properties: {"Status": "DOING"}` (ou `DONE`, `BLOCKED` + `"Motivo do bloqueio"`). A propriedade "ID" se chama `userDefined:ID` na escrita. Tarefa nova ou reordenada no `tasks.md`: criar o card e ajustar "Ordem".

## Estado em 30/10/2026

S1 a S6 (T1.1 a T6.15) inteiras em `DONE`.

## Cards (page id)

| Tarefa | Page id |
|---|---|
| T1.1 | `407058f8-4000-810b-a2e4-f195c614e1da` |
| T1.2 | `407058f8-4000-813c-a984-f80b922c61f1` |
| T1.3 | `407058f8-4000-816f-979a-e640b06d1f3d` |
| T1.4 | `407058f8-4000-8173-bea8-d728d32c003a` |
| T1.5 | `407058f8-4000-8171-b0bf-ee43b23c0fde` |
| T1.6 | `407058f8-4000-81ae-aea3-ea70c4e855f6` |
| T1.7 | `407058f8-4000-81e2-a48d-d037e46dec15` |
| T1.8 | `407058f8-4000-81c2-b409-c241eb0d3626` |
| T1.9 | `407058f8-4000-81c4-82ac-eaa462977142` |
| T1.10 | `407058f8-4000-81eb-bbec-dad035dadd7c` |
| T1.11 | `407058f8-4000-8104-b299-fc6156b45d65` |
| T1.12 | `407058f8-4000-81b7-85ee-c6af5b128a64` |
| T1.13 | `407058f8-4000-81b6-b2bd-e8a44deb20a6` |
| T1.14 | `407058f8-4000-81d2-8f7c-f4106b349e30` |
| T1.15 | `407058f8-4000-81c7-a524-cfa687cc0118` |
| T2.1 | `407058f8-4000-811d-9117-e9948f4368ca` |
| T2.2 | `407058f8-4000-817e-9e93-ea8a8fe8de7a` |
| T2.3 | `407058f8-4000-810a-a8d5-c8415d4936f6` |
| T2.4 | `407058f8-4000-81e1-b529-f97f756a28f9` |
| T2.5 | `407058f8-4000-8180-88fc-c42350c07b36` |
| T2.6 | `407058f8-4000-812f-b332-f6f7ee82edc1` |
| T2.7 | `407058f8-4000-8161-8ab7-c013aad1ce62` |
| T2.8 | `407058f8-4000-8146-abf3-fd77aed57cdf` |
| T2.9 | `407058f8-4000-8116-8218-c49054909659` |
| T3.1 | `407058f8-4000-817e-a31d-e2fcf5b03bdf` |
| T3.2 | `407058f8-4000-81d8-95e0-e851952393b1` |
| T3.3 | `407058f8-4000-81ca-9b21-d2887b3959d0` |
| T3.4 | `407058f8-4000-813c-920e-d95ce184cf5e` |
| T3.5 | `407058f8-4000-81a1-9b32-f165b7b0c7bc` |
| T4.1 | `407058f8-4000-817f-b086-d206e78ed00b` |
| T4.2 | `407058f8-4000-8165-bc95-e8fbdf6c6b8e` |
| T4.3 | `407058f8-4000-8131-a746-cf0c3919f00b` |
| T4.4 | `407058f8-4000-81d5-ab75-f07cd2457fe6` |
| T4.5 | `407058f8-4000-81cc-984f-eb46b4e58175` |
| T4.6 | `407058f8-4000-8187-b5c5-d698eb680dea` |
| T4.7 | `407058f8-4000-8112-b5d7-caba6f7f3655` |
| T5.1 | `407058f8-4000-8106-913e-ddbdb1173e6f` |
| T5.2 | `407058f8-4000-81c9-9dc8-c52c0c61b8a4` |
| T5.3 | `407058f8-4000-8126-9e6a-f520dd810a0f` |
| T5.4 | `407058f8-4000-818a-8db5-fe34ffb16044` |
| T5.5 | `407058f8-4000-814e-a86d-de808ec9acda` |
| T5.6 | `407058f8-4000-8177-be56-c9daa07ccf81` |
| T5.7 | `407058f8-4000-8161-81a1-d9ce8c2d282f` |
| T5.8 | `407058f8-4000-81d1-b67f-d331e343af66` |
| T5.9 | `407058f8-4000-8101-a847-ebe007066b93` |
| T5.10 | `407058f8-4000-8169-8c6f-f2e61fba0d49` |
| T5.11 | `407058f8-4000-8198-a327-cf17736cf768` |
| T6.1 | `407058f8-4000-81e7-bcc4-e7aaea9a84f5` |
| T6.2 | `407058f8-4000-817c-a1e1-e3a2f12ecf72` |
| T6.3 | `407058f8-4000-8111-b419-f08bbe93dd27` |
| T6.4 | `407058f8-4000-8101-be75-d94cea13ccbf` |
| T6.5 | `407058f8-4000-8186-bc22-e3b1f25af09d` |
| T6.6 | `407058f8-4000-8125-95fb-e0067a2abc16` |
| T6.7 | `407058f8-4000-8131-9486-d313ad351875` |
| T6.8 | `407058f8-4000-81d2-bb3b-dd7d2aaa7126` |
| T6.9 | `407058f8-4000-810d-bbca-dc8d2c935982` |
| T6.10 | `409058f8-4000-811c-8b6f-fba07624be45` |
| T6.11 | `409058f8-4000-81d3-b99a-fc89fa1a3d4f` |
| T6.12 | `409058f8-4000-811d-903f-d3f1cf7c861b` |
| T6.13 | `409058f8-4000-81d0-969a-fc94cd49d31a` |
| T6.14 | `409058f8-4000-8168-857a-de06c3704c5a` |
| T6.15 | `409058f8-4000-8157-849c-fd7abe95320e` |
