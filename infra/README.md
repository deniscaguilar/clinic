# Infraestrutura (OpenTofu)

Decisões em `docs/adr/0004-infraestrutura-aws.md`. Região padrão: **sa-east-1** (variável).

```
infra/
  modules/   módulos reutilizáveis (network, database, compute, observability, iam)
  stacks/    foundation → data → app → observability (cada uma com state próprio em S3)
  envs/      valores por ambiente (prod, e smoke efêmero)
```

Regras
- Stacks separadas por frequência de mudança; o deploy da aplicação é pipeline à parte.
- Providers com versão fixada; `tofu validate`, `tflint` e `checkov` no CI.
- Nenhum `apply` sem aprovação humana explícita (gera custo).
- GitHub Actions autentica na AWS por OIDC, sem chaves estáticas.
- Sem NAT Gateway; RDS em sub-rede privada, sem acesso público.

Ainda sem código de infraestrutura: será escrito depois da spec e do plano do M0.
