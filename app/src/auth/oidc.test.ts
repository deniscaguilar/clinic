import { describe, expect, it } from 'vitest'
import { buildOidcSettings } from './oidc'

describe('OIDC settings', () => {
  const settings = buildOidcSettings({ authority: 'http://idp.test/clinic', clientId: 'clinic-app', origin: 'http://app.test' })

  it('uses the authorization code flow (PKCE is applied by the library for it)', () => {
    expect(settings.response_type).toBe('code')
  })

  it('points to the configured provider and returns to the app callback', () => {
    expect(settings.authority).toBe('http://idp.test/clinic')
    expect(settings.client_id).toBe('clinic-app')
    expect(settings.redirect_uri).toBe('http://app.test/auth/callback')
  })

  it('keeps the tokens in the session storage, never in local storage', () => {
    expect(settings.userStore).toBeDefined()
    expect((settings.userStore as unknown as { _store: Storage })._store).toBe(window.sessionStorage)
  })
})
