# Fluxo de trabalho

Como trabalhamos no dia a dia. Regras detalhadas para a IA estão em `../CLAUDE.md`.

## 1. Do pedido ao código (SDD)

1. **Spec** (`specs/<module>/<feature>/spec.md`): problema, critérios de aceite, fora de escopo. Aprovação humana.
2. **Plano** (`plan.md`): design técnico. Aprovação humana.
3. **Tarefas** (`tasks.md`): passos pequenos e testáveis.
4. **Implementação test-first**: teste vermelho, código, teste verde, refatoração.
5. **Pull request** com spec e testes atualizados e `./mvnw verify` verde.

### Revisão e aprovação com Plannotator

Todo documento do fluxo acima (spec, plano, ADR, arquitetura) passa pelo [Plannotator](https://github.com/backnotprop/plannotator) antes de ser aprovado:

1. A IA escreve com status `Rascunho` e abre a revisão: `/plannotator-annotate <arquivo ou pasta>`.
2. Você comenta e aprova no navegador; o feedback volta para a IA.
3. A IA ajusta o documento. Se a mudança for relevante, a revisão é reaberta.
4. Com a sua aprovação explícita, o status vira `Aprovada` e a caixa "Aprovada por" é preenchida.

Configuração recomendada: `PLANNOTATOR_SHARE=disabled`. Os documentos contêm informação de negócio do cliente (faturamento, estratégia), então nada de link compartilhado.

## 2. Git

- Trunk-based: `main` sempre estável; branches curtas.
- **Branches em português, sem acentos**, com o tipo em inglês: `feat/m0-tenancy-por-clinica`, `fix/rls-vazando-pacientes`.
- **Commits convencionais:** tipo em inglês, descrição em português: `feat: cria cadastro de clínicas`, `fix: corrige filtro de tenant em pacientes`, `test: cobre isolamento de clínicas`.
- Tipos: `feat`, `fix`, `refactor`, `test`, `docs`, `chore`, `ci`, `build`.
- Pull request mesmo trabalhando sozinho: o CI é o portão.
- Releases com tag semântica (`v0.1.0`).
- Decisão de arquitetura nova vira ADR em `docs/adr/`.

## 3. Ambiente local

```bash
cp docker/env.example docker/.env
docker compose -f docker/compose.yaml --env-file docker/.env up -d
./mvnw verify
```

Serviços e portas na raiz do projeto, em `README.md`.

## 4. Portão do CI

Build, testes unitários e de integração, cobertura mínima em `domain` e `application`, ArchUnit e Modulith, scan de dependências e da imagem (sem vulnerabilidade Alta ou Crítica), lint de infra e SBOM. Detalhes em ADR-0005 e ADR-0008.

## 5. Antes de qualquer go-live

- Restauração de snapshot do RDS testada.
- Ambiente de fumaça efêmero na AWS (apply, testes, destroy).
- `plan` do OpenTofu revisado por humano.
- Revisão jurídica de LGPD antes de entrar dado clínico real (Etapa 4).
