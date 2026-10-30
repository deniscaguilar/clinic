#!/usr/bin/env bash
# Scan de dependências com OSV-Scanner sobre o SBOM CycloneDX do build.
# Reprova com vulnerabilidade de severidade Alta ou Crítica (CVSS >= 7.0) ou sem severidade conhecida.
# Supressões: osv-scanner.toml, com `reason` e `ignoreUntil` (data de expiração) obrigatórios.
set -euo pipefail

sbom="${1:?uso: osv-gate.sh <sbom.cdx.json>}"
root="$(cd "$(dirname "$0")/../.." && pwd)"
out="$(mktemp)"
trap 'rm -f "$out"' EXIT

# Imagem fixada por digest (atualizada pelo Dependabot, ecossistema docker não cobre scripts: revisar ao atualizar).
osv_image="ghcr.io/google/osv-scanner@sha256:4f3b7e2c64b9d38a5febc5a7e4a3dfef1d1362f726dea7a2ef4762b46469fcd0" # v2.7.0

# O código de saída 1 do scanner significa "achou vulnerabilidade"; quem decide é o filtro abaixo.
set +e
docker run --rm \
  -v "$(cd "$(dirname "$sbom")" && pwd)/$(basename "$sbom"):/in/sbom.cdx.json:ro" \
  -v "$root/osv-scanner.toml:/in/osv-scanner.toml:ro" \
  "$osv_image" scan source --config /in/osv-scanner.toml --format json -L /in/sbom.cdx.json > "$out"
status=$?
set -e
if [ "$status" -gt 1 ]; then
  echo "OSV-Scanner falhou (código $status)" >&2
  exit "$status"
fi

blocking="$(jq -r '
  [ .results[]?.packages[]?
    | . as $p
    | .groups[]?
    | select(((.max_severity // "") == "") or ((.max_severity | tonumber) >= 7.0))
    | "\($p.package.name)@\($p.package.version)  \(.ids | join(", "))  CVSS=\(.max_severity // "desconhecida")" ]
  | unique | .[]' "$out")"

if [ -n "$blocking" ]; then
  echo "Vulnerabilidades Alta ou Crítica (ou sem severidade):" >&2
  echo "$blocking" >&2
  exit 1
fi
echo "Sem vulnerabilidade Alta ou Crítica."
