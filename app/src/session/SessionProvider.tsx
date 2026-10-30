import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useApi } from '../api/ApiProvider'
import type { components } from '../api/schema'
import { useClinic } from '../clinic/ClinicProvider'

export type Session = components['schemas']['SessionResponse']

interface SessionContextValue {
  session: Session
  can(permission: string): boolean
  /** Recarrega a sessão sem sair da tela (ex.: depois de salvar o branding). */
  refresh(): Promise<void>
}

const SessionContext = createContext<SessionContextValue | null>(null)

export function useSession(): SessionContextValue {
  const value = useContext(SessionContext)
  if (!value) throw new Error('useSession requires a SessionProvider')
  return value
}

type Load = { clinicId: string; state: 'loading' } | { clinicId: string; state: 'error' } | { clinicId: string; state: 'ready'; session: Session }

/** Carrega `/session` da clínica ativa (usuário, perfis, permissões e branding) e a recarrega quando a clínica muda. */
export function SessionProvider({ children }: { children: ReactNode }) {
  const api = useApi()
  const { t } = useTranslation()
  const { clinicId } = useClinic()
  const [load, setLoad] = useState<Load | null>(null)

  useEffect(() => {
    if (!clinicId) return
    let cancelled = false
    setLoad({ clinicId, state: 'loading' })
    api
      .GET('/api/v1/session')
      .then(({ data }) => {
        if (cancelled) return
        setLoad(data ? { clinicId, state: 'ready', session: data } : { clinicId, state: 'error' })
      })
      .catch(() => !cancelled && setLoad({ clinicId, state: 'error' }))
    return () => {
      cancelled = true
    }
  }, [api, clinicId])

  const refresh = useCallback(async () => {
    if (!clinicId) return
    const { data } = await api.GET('/api/v1/session')
    if (data) setLoad({ clinicId, state: 'ready', session: data })
  }, [api, clinicId])

  const session = load?.clinicId === clinicId && load.state === 'ready' ? load.session : null
  const value = useMemo<SessionContextValue | null>(() => {
    if (!session) return null
    const granted = new Set(session.permissions ?? [])
    return { session, can: (permission) => granted.has(permission), refresh }
  }, [session, refresh])

  if (!value) {
    const failed = load?.clinicId === clinicId && load.state === 'error'
    return failed ? <p role="alert">{t('session.loadError')}</p> : <p>{t('common.loading')}</p>
  }
  return <SessionContext.Provider value={value}>{children}</SessionContext.Provider>
}
