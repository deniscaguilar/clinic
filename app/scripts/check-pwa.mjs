// Confere o build do PWA: manifest e service worker existem, e o service worker só serve o shell (nada de cache de API).
// Uso: node scripts/check-pwa.mjs [pasta-do-build]   (padrão: dist)
import { existsSync, readFileSync } from 'node:fs'
import { join } from 'node:path'

const dist = process.argv[2] ?? 'dist'
const problems = []
for (const file of ['manifest.webmanifest', 'sw.js', 'index.html']) {
  if (!existsSync(join(dist, file))) problems.push(`missing ${file}`)
}
if (problems.length === 0) {
  const sw = readFileSync(join(dist, 'sw.js'), 'utf8')
  const routes = sw.match(/registerRoute\(/g) ?? []
  if (routes.length !== 1 || !sw.includes('NavigationRoute')) problems.push('service worker must register only the navigation route (no runtime caching)')
  if (!sw.includes('denylist:[/^\\/api\\//]')) problems.push('navigation fallback must exclude /api')
  if (/\/api\/v1/.test(sw)) problems.push('service worker must not reference API endpoints')
}
if (problems.length > 0) {
  console.error(problems.map((p) => `PWA check failed: ${p}`).join('\n'))
  process.exit(1)
}
console.log('PWA check ok')
