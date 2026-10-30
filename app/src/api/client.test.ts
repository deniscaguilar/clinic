import { describe, expect, it } from 'vitest'
import { fakeFetch } from '../test/fakes'
import { createApiClient } from './client'

const base = 'http://api.test'

function build(clinicId: string | null, token: string | null = 'abc') {
  const fetch = fakeFetch({ '/api/v1/me': {}, '/api/v1/me/clinics': [], '/api/v1/session': {}, '/api/v1/admin/clinics': [], '/api/v1/roles': [] })
  let clinic = clinicId
  const client = createApiClient({ getToken: async () => token, getClinicId: () => clinic }, { baseUrl: base, fetch })
  return { client, fetch, setClinic: (id: string | null) => (clinic = id) }
}

describe('API client', () => {
  it('attaches the bearer token to every request', async () => {
    const { client, fetch } = build('clinic-a')
    await client.GET('/api/v1/me')
    expect(fetch.calls[0]?.headers.get('Authorization')).toBe('Bearer abc')
  })

  it('sends X-Clinic-Id on clinic-scoped endpoints', async () => {
    const { client, fetch } = build('clinic-a')
    await client.GET('/api/v1/roles')
    expect(fetch.calls[0]?.headers.get('X-Clinic-Id')).toBe('clinic-a')
  })

  it.each(['/api/v1/me', '/api/v1/me/clinics', '/api/v1/admin/clinics'] as const)('does not send X-Clinic-Id on %s', async (path) => {
    const { client, fetch } = build('clinic-a')
    await client.GET(path as '/api/v1/me')
    expect(fetch.calls[0]?.headers.has('X-Clinic-Id')).toBe(false)
    expect(fetch.calls[0]?.headers.get('Authorization')).toBe('Bearer abc')
  })

  it('follows the active clinic when the user switches', async () => {
    const { client, fetch, setClinic } = build('clinic-a')
    await client.GET('/api/v1/session')
    setClinic('clinic-b')
    await client.GET('/api/v1/session')
    expect(fetch.calls.map((call) => call.headers.get('X-Clinic-Id'))).toEqual(['clinic-a', 'clinic-b'])
  })

  it('omits the headers it does not have', async () => {
    const { client, fetch } = build(null, null)
    await client.GET('/api/v1/roles')
    expect(fetch.calls[0]?.headers.has('Authorization')).toBe(false)
    expect(fetch.calls[0]?.headers.has('X-Clinic-Id')).toBe(false)
  })
})
