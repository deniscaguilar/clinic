#!/usr/bin/env bash
# `npm audit` em cada projeto TypeScript com lockfile (app/). Falha com severidade Alta ou Crítica.
set -euo pipefail
cd "$(dirname "$0")/../.."

found=0
while IFS= read -r lock; do
  dir="$(dirname "$lock")"
  found=1
  echo "== npm audit em $dir"
  (cd "$dir" && npm audit --audit-level=high)
done < <(find app -name package-lock.json -not -path '*/node_modules/*' 2>/dev/null)

[ "$found" -eq 1 ] || echo "Nenhum package-lock.json ainda."
