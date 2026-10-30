# Backend

Monolito modular em Kotlin + Spring Boot, build com Maven (`./mvnw`). Regras de arquitetura em `../CLAUDE.md` e em `../docs/adr/`.

Esqueleto gerado na T1.3 do M0 (`../specs/platform/m0-foundation/`). Ainda sem módulos de negócio.

## Como rodar

Precisa do JDK 25 (o enforcer reprova outro). Com SDKMAN: `sdk env` nesta pasta (o `.sdkmanrc` aponta para o JDK 25 instalado). Depois:

```
./mvnw verify          # unitários + integração + cobertura + SBOM
./mvnw verify -Pscan   # acrescenta o OWASP dependency-check (exige NVD_API_KEY)
```

- Unitários `*Test` (Surefire) e integração `*IT` (Failsafe).
- Portão JaCoCo: linhas e branches ≥ 85% só em `domain` e `application` (o filtro está na configuração do `check`).
- Enforcer: JDK 25, Maven ≥ 3.9, convergência de dependências.
- SBOM CycloneDX 1.6 em `target/classes/META-INF/sbom/application.cdx.json` (vai dentro do jar).

## Convenção de migração com RLS (ADR-0010)

Toda tabela de negócio com `tenant_id` termina a migração que a cria com:

```sql
select platform.apply_tenant_rls('<schema>', '<tabela>');
```

A função (migração `platform/V1`) liga `ENABLE` e `FORCE ROW LEVEL SECURITY` e cria a política `tenant_isolation`
(`tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid`, com `USING` e `WITH CHECK`). Tabela com política
própria (ex.: `clinic`, `membership`) cria as suas à mão, mas ainda precisa de `ENABLE` e `FORCE`.

`RlsCatalogIT` varre o catálogo e reprova: tabela com `tenant_id` sem RLS habilitada, forçada e com política; e tabela
**sem** `tenant_id` que não esteja em `RlsCatalog.globalTables` (cada entrada com justificativa no PR).

## Regras de teste que já existem

- **Toda tabela nova com `tenant_id`** precisa de um inserter em `src/test/.../support/TenantFixtures.kt`; sem ele o `TenantIsolationIT` genérico reprova. Tabela append-only entra em `appendOnlyTables`; tabela que o escopo `platform` pode abrir entra em `platformScopeTables` (só as da ADR-0010).
- **Tabela sem `tenant_id`** precisa estar em `RlsCatalog.globalTables`, com justificativa no PR.
- **Contrato OpenAPI**: `OpenApiContractIT` compara com `openapi/openapi.yaml`; para aceitar a mudança, `./mvnw verify -Dopenapi.update=true` e versionar o diff.
- **Teste de integração**: estender `DatabaseIT` (Postgres 17.11 com o mesmo `docker/postgres/init/01-roles.sh` do Compose). Teste que precisa de token: subir `MockOAuth2Server` e apontar `spring.security.oauth2.resourceserver.jwt.issuer-uri` por `@DynamicPropertySource`.
- **Auditoria**: `AuditEvent.details` aceita só nomes de campo. Erro de negócio: lançar `ApiException(kind, code)`; a resposta sai em Problem Details sem eco do valor.

## Armadilhas já encontradas

- `/*` dentro de comentário KDoc abre comentário aninhado em Kotlin (ex.: `/admin/**`). Escreva "rotas de /admin".
- `MockMvc` lê a resposta como ISO-8859-1 por padrão: `getContentAsString(Charsets.UTF_8)` ao comparar texto com acento.
- Marcar `tracer.currentSpan()` cai num span filho da cadeia de segurança; para o span do servidor, marque o contexto de observação (`ObservationTagger`).
- O Spring preenche `instance` do `ProblemDetail` com a URL da requisição; mantido em `about:blank`.
- Spring MVC registra a mensagem de algumas exceções em `WARN`; os resolvedores padrão estão em `ERROR` no `application.properties`.
