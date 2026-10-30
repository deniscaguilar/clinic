import { fakeFetch } from './fakes'

type Routes = Parameters<typeof fakeFetch>[0]

/** `fetch` falso de uma clínica com uma só opção (escolhida sozinha) e a sessão dada; `extra` traz as rotas da tela. */
export function clinicFetch(
  extra: Routes,
  permissions: string[] = [],
  branding: object = { displayName: 'Clínica Aurora', primaryColor: '#112233', secondaryColor: '#223344', onPrimary: '#FFFFFF' },
) {
  return fakeFetch({
    '/api/v1/me/clinics': [{ id: 'clinic-a', name: 'Clínica Aurora' }],
    '/api/v1/session': { user: { id: 'u' }, clinic: { id: 'clinic-a', name: 'Clínica Aurora' }, roles: [], permissions, branding },
    ...extra,
  })
}

export const calls = (fetch: ReturnType<typeof fakeFetch>, method: string, path: string) =>
  fetch.calls.filter((c) => c.method === method && new URL(c.url).pathname === path)
