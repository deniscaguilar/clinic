const DEFAULT_FAVICON = '/favicon.svg'
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/
const COLOR_VARIABLES = { primaryColor: '--color-primary', secondaryColor: '--color-secondary', onPrimary: '--color-on-primary' } as const

export interface Branding {
  displayName?: string
  primaryColor?: string
  secondaryColor?: string
  onPrimary?: string
  logoUrl?: string | null
  faviconUrl?: string | null
}

/** Só `http(s)`: o endereço vem de dado da clínica e vai para um atributo `href`/`src`. */
export function isHttpUrl(value: string | null | undefined): value is string {
  if (!value) return false
  try {
    const protocol = new URL(value).protocol
    return protocol === 'https:' || protocol === 'http:'
  } catch {
    return false
  }
}

function faviconLink(doc: Document): HTMLLinkElement {
  let link = doc.querySelector<HTMLLinkElement>('link[rel="icon"]')
  if (!link) {
    link = doc.createElement('link')
    link.rel = 'icon'
    doc.head.appendChild(link)
  }
  return link
}

/** Aplica a identidade visual da clínica: variáveis CSS, título da página e favicon. Valor fora do formato é ignorado. */
export function applyBranding(doc: Document, branding: Branding): void {
  const style = doc.documentElement.style
  for (const [field, variable] of Object.entries(COLOR_VARIABLES)) {
    const value = branding[field as keyof typeof COLOR_VARIABLES]
    if (value && HEX_COLOR.test(value)) style.setProperty(variable, value)
    else style.removeProperty(variable)
  }
  if (branding.displayName) doc.title = branding.displayName
  faviconLink(doc).setAttribute('href', isHttpUrl(branding.faviconUrl) ? branding.faviconUrl : DEFAULT_FAVICON)
}

/** Volta ao tema padrão (sem clínica ativa). O título é do chamador. */
export function clearBranding(doc: Document): void {
  for (const variable of Object.values(COLOR_VARIABLES)) doc.documentElement.style.removeProperty(variable)
  faviconLink(doc).setAttribute('href', DEFAULT_FAVICON)
}
