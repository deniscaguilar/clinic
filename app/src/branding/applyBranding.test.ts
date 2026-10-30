import { afterEach, describe, expect, it } from 'vitest'
import { applyBranding, clearBranding } from './applyBranding'

const root = () => document.documentElement
const variable = (name: string) => root().style.getPropertyValue(name)

afterEach(() => clearBranding(document))

describe('applyBranding', () => {
  const branding = { displayName: 'Clínica Aurora', primaryColor: '#112233', secondaryColor: '#445566', onPrimary: '#FFFFFF', logoUrl: null, faviconUrl: 'https://files.test/fav.png' }

  it('sets the theme as CSS variables on the root element', () => {
    applyBranding(document, branding)
    expect(variable('--color-primary')).toBe('#112233')
    expect(variable('--color-secondary')).toBe('#445566')
    expect(variable('--color-on-primary')).toBe('#FFFFFF')
  })

  it('sets the page title and the favicon', () => {
    applyBranding(document, branding)
    expect(document.title).toBe('Clínica Aurora')
    expect(document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.href).toBe('https://files.test/fav.png')
  })

  it('goes back to the default favicon when the clinic has none', () => {
    applyBranding(document, branding)
    applyBranding(document, { ...branding, faviconUrl: null })
    expect(document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.getAttribute('href')).toBe('/favicon.svg')
  })

  it('ignores a value that is not a hex color, so the clinic data cannot inject CSS', () => {
    applyBranding(document, { ...branding, primaryColor: 'red; background: url(x)', onPrimary: 'javascript:1' })
    expect(variable('--color-primary')).toBe('')
    expect(variable('--color-on-primary')).toBe('')
    expect(variable('--color-secondary')).toBe('#445566')
  })

  it('drops a previous value when the new one is not valid', () => {
    applyBranding(document, branding)
    applyBranding(document, { ...branding, primaryColor: 'nope' })
    expect(variable('--color-primary')).toBe('')
  })

  it('ignores a favicon that is not an http(s) URL', () => {
    applyBranding(document, { ...branding, faviconUrl: 'javascript:alert(1)' })
    expect(document.querySelector<HTMLLinkElement>('link[rel="icon"]')?.getAttribute('href')).toBe('/favicon.svg')
  })

  it('clears the variables when leaving the clinic', () => {
    applyBranding(document, branding)
    clearBranding(document)
    expect(variable('--color-primary')).toBe('')
    expect(variable('--color-secondary')).toBe('')
    expect(variable('--color-on-primary')).toBe('')
  })
})
