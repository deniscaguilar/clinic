import type { TFunction } from 'i18next'

export interface FieldIssue {
  field: string
  rule: string
}

/** Erro da API em Problem Details (RFC 9457): `code` estável e, na validação, os campos com a regra violada. */
export interface Problem {
  code: string
  status?: number
  fields: FieldIssue[]
}

export class ApiError extends Error {
  constructor(readonly problem: Problem) {
    super(problem.code)
  }
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}

export function toProblem(error: unknown, status?: number): Problem {
  const body = isRecord(error) ? error : {}
  const fallback = status === 401 ? 'UNAUTHENTICATED' : status === 403 ? 'PERMISSION_DENIED' : 'UNKNOWN'
  const code = typeof body.code === 'string' && body.code ? body.code : fallback
  const issues = Array.isArray(body.errors) ? body.errors : []
  const fields = issues.flatMap((issue) =>
    isRecord(issue) && typeof issue.field === 'string' && typeof issue.rule === 'string' ? [{ field: issue.field, rule: issue.rule }] : [],
  )
  return { code, ...(status !== undefined ? { status } : {}), fields }
}

/** Desembrulha a resposta do `openapi-fetch`: devolve os dados ou lança [ApiError] (falha de rede vira `NETWORK_ERROR`). */
export async function call<R extends { data?: unknown; error?: unknown; response: Response }>(request: Promise<R>): Promise<R['data']> {
  let result: R
  try {
    result = await request
  } catch {
    throw new ApiError({ code: 'NETWORK_ERROR', fields: [] })
  }
  if (result.error !== undefined) throw new ApiError(toProblem(result.error, result.response.status))
  return result.data
}

export function asProblem(thrown: unknown): Problem {
  return thrown instanceof ApiError ? thrown.problem : { code: 'UNKNOWN', fields: [] }
}

/** Texto pt-BR do código; código que o front ainda não conhece cai no texto genérico. */
export function errorText(t: TFunction, exists: (key: string) => boolean, problem: Problem): string {
  const key = `errors.${problem.code}`
  return exists(key) ? t(key) : t('errors.UNKNOWN')
}
