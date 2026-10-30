# Specs (SDD)

Cada funcionalidade vive em `specs/<modulo>/<feature>/` com três arquivos, nesta ordem:

1. `spec.md`: o quê e por quê. Critérios de aceite em Given/When/Then. **Aprovação humana.**
2. `plan.md`: como. Casos de uso, ports, modelo de dados, impactos. **Aprovação humana.**
3. `tasks.md`: tarefas pequenas e testáveis, na ordem de execução (test-first).

Status possíveis no cabeçalho: `Rascunho` → `Aprovada` → `Implementada`.

Templates em `_templates/`. Se o código precisar divergir da spec, atualize a spec antes.

## Índice

| Módulo | Feature | Status |
|---|---|---|
| platform | `m0-foundation` | Aprovada |
