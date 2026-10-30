import type { UserManager } from 'oidc-client-ts'
import { describe, expect, it, vi } from 'vitest'
import { safeReturnTo } from './AuthService'
import { OidcAuthService } from './oidc'

describe('safeReturnTo', () => {
  it.each([
    ['/patients?page=2', '/patients?page=2'],
    ['//evil.example', '/'],
    ['https://evil.example', '/'],
    ['patients', '/'],
    [undefined, '/'],
    [42, '/'],
  ])('%s -> %s', (input, expected) => {
    expect(safeReturnTo(input)).toBe(expected)
  })
})

function service(manager: Partial<Record<keyof UserManager, unknown>>) {
  return new OidcAuthService(manager as unknown as UserManager)
}

describe('OidcAuthService', () => {
  it('passes the return path as the login state', async () => {
    const signinRedirect = vi.fn(async () => {})
    await service({ signinRedirect }).login('/patients')
    expect(signinRedirect).toHaveBeenCalledWith({ state: '/patients' })
  })

  it('returns the safe path stored in the state after the callback', async () => {
    const ok = service({ signinRedirectCallback: async () => ({ state: '/patients' }) })
    expect(await ok.completeLogin()).toBe('/patients')
    const evil = service({ signinRedirectCallback: async () => ({ state: '//evil.example' }) })
    expect(await evil.completeLogin()).toBe('/')
  })

  it('gives the access token only while it is valid', async () => {
    expect(await service({ getUser: async () => ({ access_token: 'abc', expired: false }) }).getAccessToken()).toBe('abc')
    expect(await service({ getUser: async () => ({ access_token: 'abc', expired: true }) }).getAccessToken()).toBeNull()
    expect(await service({ getUser: async () => null }).getAccessToken()).toBeNull()
  })

  it('signs out through the provider', async () => {
    const signoutRedirect = vi.fn(async () => {})
    await service({ signoutRedirect }).logout()
    expect(signoutRedirect).toHaveBeenCalledTimes(1)
  })
})
