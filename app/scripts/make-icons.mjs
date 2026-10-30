// Gera os ícones PNG do PWA (placeholder neutro: cruz branca sobre azul). Sem dependência: só `zlib` do Node.
// A identidade visual de cada clínica vem do branding (CSS variables, logo e favicon por clínica), não destes arquivos.
import { deflateSync } from 'node:zlib'
import { mkdirSync, writeFileSync } from 'node:fs'

const crcTable = Array.from({ length: 256 }, (_, n) => {
  let c = n
  for (let k = 0; k < 8; k++) c = c & 1 ? 0xedb88320 ^ (c >>> 1) : c >>> 1
  return c >>> 0
})
const crc32 = (buffer) => {
  let c = 0xffffffff
  for (const byte of buffer) c = crcTable[(c ^ byte) & 0xff] ^ (c >>> 8)
  return (c ^ 0xffffffff) >>> 0
}
const chunk = (type, data) => {
  const body = Buffer.concat([Buffer.from(type), data])
  const length = Buffer.alloc(4)
  length.writeUInt32BE(data.length)
  const crc = Buffer.alloc(4)
  crc.writeUInt32BE(crc32(body))
  return Buffer.concat([length, body, crc])
}

function png(size, { background, foreground, margin }) {
  const raw = Buffer.alloc((size * 4 + 1) * size)
  const arm = size * 0.09 // meia espessura da cruz
  const reach = size * (0.5 - margin) // meia extensão da cruz
  const centre = size / 2
  for (let y = 0; y < size; y++) {
    raw[y * (size * 4 + 1)] = 0 // filtro "nenhum"
    for (let x = 0; x < size; x++) {
      const dx = Math.abs(x + 0.5 - centre)
      const dy = Math.abs(y + 0.5 - centre)
      const onCross = (dx <= arm && dy <= reach) || (dy <= arm && dx <= reach)
      const [r, g, b] = onCross ? foreground : background
      const offset = y * (size * 4 + 1) + 1 + x * 4
      raw[offset] = r
      raw[offset + 1] = g
      raw[offset + 2] = b
      raw[offset + 3] = 255
    }
  }
  const header = Buffer.alloc(13)
  header.writeUInt32BE(size, 0)
  header.writeUInt32BE(size, 4)
  header[8] = 8 // 8 bits por canal
  header[9] = 6 // RGBA
  return Buffer.concat([
    Buffer.from([0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]),
    chunk('IHDR', header),
    chunk('IDAT', deflateSync(raw)),
    chunk('IEND', Buffer.alloc(0)),
  ])
}

const blue = [0x0f, 0x62, 0xfe]
const white = [0xff, 0xff, 0xff]
mkdirSync('public/icons', { recursive: true })
writeFileSync('public/icons/icon-192.png', png(192, { background: blue, foreground: white, margin: 0.2 }))
writeFileSync('public/icons/icon-512.png', png(512, { background: blue, foreground: white, margin: 0.2 }))
// "maskable": o conteúdo fica na área segura central (80%), com sangria da cor de fundo até a borda
writeFileSync('public/icons/icon-maskable-512.png', png(512, { background: blue, foreground: white, margin: 0.3 }))
console.log('ícones gerados em public/icons')
