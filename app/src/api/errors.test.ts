import { readdirSync, readFileSync, statSync } from 'node:fs'
import { join, resolve } from 'node:path'
import { describe, expect, it } from 'vitest'
import { createI18n } from '../i18n'
import { ApiError, asProblem, call, errorText, toProblem } from './errors'

function kotlinFiles(dir: string): string[] {
  return readdirSync(dir).flatMap((name) => {
    const path = join(dir, name)
    return statSync(path).isDirectory() ? kotlinFiles(path) : name.endsWith('.kt') ? [path] : []
  })
}

/** Os códigos de erro que o backend pode devolver, lidos do código-fonte (`ApiException`, exceções de domínio e do advice). */
function backendCodes(): string[] {
  const pattern = /(?:ApiException\([A-Za-z.]+,\s*|RegistryException\(|BrandingException\(|NotificationException\(|problem\(HttpStatus\.[A-Z_]+,\s*|throw [A-Za-z]+\()"([A-Z][A-Z0-9_]+)"/g
  const codes = new Set<string>()
  for (const file of kotlinFiles(resolve(process.cwd(), '../backend/src/main/kotlin'))) {
    for (const match of readFileSync(file, 'utf8').matchAll(pattern)) codes.add(match[1]!)
  }
  return [...codes].sort()
}

describe('toProblem', () => {
  it('reads the stable code and the field issues from a Problem Details body', () => {
    const problem = toProblem({ status: 400, code: 'VALIDATION_FAILED', errors: [{ field: 'name', rule: 'NotBlank' }] }, 400)
    expect(problem).toEqual({ code: 'VALIDATION_FAILED', status: 400, fields: [{ field: 'name', rule: 'NotBlank' }] })
  })

  it.each([
    [401, 'UNAUTHENTICATED'],
    [403, 'PERMISSION_DENIED'],
    [500, 'UNKNOWN'],
  ])('maps a body without code and status %i to %s', (status, code) => {
    expect(toProblem('', status).code).toBe(code)
    expect(toProblem({ title: 'x' }, status).code).toBe(code)
  })

  it('ignores malformed field issues', () => {
    expect(toProblem({ code: 'VALIDATION_FAILED', errors: [{ field: 1 }, null, 'x', { field: 'a', rule: 'Size' }] }, 400).fields).toEqual([{ field: 'a', rule: 'Size' }])
  })
})

describe('call', () => {
  const ok = (data: unknown) => Promise.resolve({ data, error: undefined, response: new Response(null, { status: 200 }) })

  it('returns the data of a successful call', async () => {
    expect(await call(ok({ a: 1 }))).toEqual({ a: 1 })
  })

  it('returns undefined for an empty success (204)', async () => {
    expect(await call(Promise.resolve({ data: undefined, error: undefined, response: new Response(null, { status: 204 }) }))).toBeUndefined()
  })

  it('throws an ApiError carrying the problem when the API answers with an error', async () => {
    const failed = Promise.resolve({ data: undefined, error: { code: 'CLINIC_NOT_FOUND' }, response: new Response(null, { status: 404 }) })
    await expect(call(failed)).rejects.toMatchObject({ problem: { code: 'CLINIC_NOT_FOUND', status: 404 } })
    await expect(call(failed)).rejects.toBeInstanceOf(ApiError)
  })

  it('turns a network failure into NETWORK_ERROR', async () => {
    await expect(call(Promise.reject(new TypeError('failed to fetch')))).rejects.toMatchObject({ problem: { code: 'NETWORK_ERROR' } })
  })
})

describe('asProblem', () => {
  it('keeps the problem of an ApiError and treats anything else as unknown', () => {
    expect(asProblem(new ApiError({ code: 'X', fields: [] })).code).toBe('X')
    expect(asProblem(new Error('boom')).code).toBe('UNKNOWN')
  })
})

describe('error texts', () => {
  const i18n = createI18n({ strict: true })
  const t = i18n.t.bind(i18n)

  it('every error code the backend can return has a pt-BR text', () => {
    const codes = backendCodes()
    expect(codes.length).toBeGreaterThan(60) // a varredura enxerga o backend
    const missing = codes.filter((code) => !i18n.exists(`errors.${code}`))
    expect(missing).toEqual([])
  })

  it('has a text for the codes the front itself produces', () => {
    for (const code of ['UNKNOWN', 'NETWORK_ERROR', 'UNAUTHENTICATED']) expect(i18n.exists(`errors.${code}`)).toBe(true)
  })

  it('shows the text of the code, and the generic one for a code it does not know', () => {
    expect(errorText(t, i18n.exists.bind(i18n), { code: 'CLINIC_NOT_FOUND', fields: [] })).toBe('Clínica não encontrada.')
    expect(errorText(t, i18n.exists.bind(i18n), { code: 'SOME_FUTURE_CODE', fields: [] })).toBe(t('errors.UNKNOWN'))
  })
})
