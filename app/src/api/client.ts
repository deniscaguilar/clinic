import createClient, { type Middleware } from 'openapi-fetch'
import type { paths } from './schema'

/**
 * O contrato declara `X-Clinic-Id` em cada operação da clínica; o middleware o injeta, então a chamada não precisa
 * (nem deve) repeti-lo. Os demais parâmetros continuam tipados pelo contrato.
 */
type WithoutClinicHeader<Operation> = Operation extends { parameters: infer P }
  ? Omit<Operation, 'parameters'> & { parameters: Omit<P, 'header'> & { header?: never } }
  : Operation
type ClientPaths = { [Path in keyof paths]: { [Method in keyof paths[Path]]: WithoutClinicHeader<paths[Path][Method]> } }

export interface ApiContext {
  getToken(): Promise<string | null>
  getClinicId(): string | null
}

interface Options {
  /** Vazio no navegador (o Vite ou o proxy reverso encaminha `/api`); absoluto nos testes. */
  baseUrl?: string
  fetch?: (request: Request) => Promise<Response>
}

/** Endpoints que valem fora de uma clínica ativa (o backend não exige `X-Clinic-Id` neles). */
const OUTSIDE_CLINIC = [/^\/api\/v1\/me(\/|$)/, /^\/api\/v1\/admin(\/|$)/]

export function needsClinic(schemaPath: string): boolean {
  return !OUTSIDE_CLINIC.some((pattern) => pattern.test(schemaPath))
}

/** Cliente tipado pelo contrato OpenAPI, com o token e a clínica ativa lidos no momento de cada requisição. */
export function createApiClient(context: ApiContext, { baseUrl = '', fetch }: Options = {}) {
  const client = createClient<ClientPaths>({ baseUrl, ...(fetch ? { fetch } : {}) })
  const attach: Middleware = {
    async onRequest({ request, schemaPath }) {
      const token = await context.getToken()
      if (token) request.headers.set('Authorization', `Bearer ${token}`)
      const clinicId = context.getClinicId()
      if (clinicId && needsClinic(schemaPath)) request.headers.set('X-Clinic-Id', clinicId)
      return request
    },
  }
  client.use(attach)
  return client
}

export type ApiClient = ReturnType<typeof createApiClient>
