import { vi } from 'vitest'
import type { AuthService } from '../auth/AuthService'

/** Serviço de autenticação falso: sem rede, sem redirecionamento. */
export function fakeAuth(token: string | null = 'token-1'): AuthService & {
  login: ReturnType<typeof vi.fn>
  logout: ReturnType<typeof vi.fn>
  completeLogin: ReturnType<typeof vi.fn>
} {
  let current = token
  return {
    login: vi.fn(async () => {}),
    logout: vi.fn(async () => {
      current = null
    }),
    completeLogin: vi.fn(async () => {
      await new Promise((resolve) => setTimeout(resolve, 5)) // o token só chega depois da rede
      current = current ?? 'token-1'
      return '/'
    }),
    getAccessToken: vi.fn(async () => current),
  } as never
}

export function jsonResponse(body: unknown, status = 200): Response {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } })
}

/** fetch falso que registra as requisições e responde por caminho (ou por `MÉTODO /caminho`, que tem prioridade). */
export function fakeFetch(routes: Record<string, unknown | ((request: Request) => Response)>) {
  const calls: Request[] = []
  const fn = vi.fn(async (input: Request) => {
    calls.push(input)
    const path = new URL(input.url).pathname
    const route = routes[`${input.method} ${path}`] ?? routes[path]
    if (route === undefined) return jsonResponse({ code: 'NOT_FOUND' }, 404)
    if (route instanceof Response) return route.clone()
    return typeof route === 'function' ? (route as (request: Request) => Response)(input) : jsonResponse(route)
  })
  return Object.assign(fn, { calls })
}

/** Corpo JSON de uma requisição registrada por [fakeFetch]. */
export async function bodyOf(request: Request): Promise<unknown> {
  return JSON.parse(await request.clone().text())
}

export function problem(code: string, status: number, errors?: { field: string; rule: string }[]): Response {
  return new Response(JSON.stringify({ status, title: 'x', code, ...(errors ? { errors } : {}) }), {
    status,
    headers: { 'Content-Type': 'application/problem+json' },
  })
}
