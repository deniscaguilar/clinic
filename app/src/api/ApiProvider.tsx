import { createContext, useContext, useMemo, type ReactNode } from 'react'
import { useAuth } from '../auth/AuthProvider'
import { useClinic } from '../clinic/ClinicProvider'
import { createApiClient, type ApiClient } from './client'

const ApiContext = createContext<ApiClient | null>(null)

export function useApi(): ApiClient {
  const client = useContext(ApiContext)
  if (!client) throw new Error('useApi requires an ApiProvider')
  return client
}

export function ApiProvider({ baseUrl, fetch, children }: { baseUrl?: string; fetch?: (request: Request) => Promise<Response>; children: ReactNode }) {
  const { service } = useAuth()
  const { getClinicId } = useClinic()
  const client = useMemo(
    () => createApiClient({ getToken: () => service.getAccessToken(), getClinicId }, { ...(baseUrl !== undefined ? { baseUrl } : {}), ...(fetch ? { fetch } : {}) }),
    [service, getClinicId, baseUrl, fetch],
  )
  return <ApiContext.Provider value={client}>{children}</ApiContext.Provider>
}
