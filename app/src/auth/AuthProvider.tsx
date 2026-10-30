import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { useLocation, useNavigate } from 'react-router'
import type { AuthService } from './AuthService'

type Status = 'loading' | 'authenticated' | 'anonymous'

interface AuthContextValue {
  service: AuthService
  status: Status
  refresh(): Promise<void>
}

const AuthContext = createContext<AuthContextValue | null>(null)

export function useAuth(): AuthContextValue {
  const value = useContext(AuthContext)
  if (!value) throw new Error('useAuth requires an AuthProvider')
  return value
}

export function AuthProvider({ service, children }: { service: AuthService; children: ReactNode }) {
  const [status, setStatus] = useState<Status>('loading')
  const refresh = useCallback(async () => {
    setStatus((await service.getAccessToken()) ? 'authenticated' : 'anonymous')
  }, [service])
  useEffect(() => {
    void refresh()
  }, [refresh])
  const value = useMemo(() => ({ service, status, refresh }), [service, status, refresh])
  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>
}

/** Quem não tem sessão vai ao provedor de identidade e volta para o mesmo caminho. */
export function RequireAuth({ children }: { children: ReactNode }) {
  const { service, status } = useAuth()
  const { t } = useTranslation()
  const location = useLocation()
  const redirected = useRef(false)
  useEffect(() => {
    if (status === 'anonymous' && !redirected.current) {
      redirected.current = true
      void service.login(location.pathname + location.search)
    }
  }, [status, service, location])
  if (status !== 'authenticated') return <p>{t(status === 'loading' ? 'common.loading' : 'auth.redirecting')}</p>
  return <>{children}</>
}

/** Rota `/auth/callback`: troca o código por tokens e volta ao caminho de origem. */
export function AuthCallback() {
  const { service, refresh } = useAuth()
  const { t } = useTranslation()
  const navigate = useNavigate()
  const started = useRef(false)
  const [failed, setFailed] = useState(false)
  useEffect(() => {
    if (started.current) return
    started.current = true
    service
      .completeLogin()
      .then(async (returnTo) => {
        await refresh()
        navigate(returnTo, { replace: true })
      })
      .catch(() => setFailed(true))
  }, [service, refresh, navigate])
  if (failed) {
    return (
      <div role="alert">
        <p>{t('auth.callbackError')}</p>
        <button onClick={() => void service.login('/')}>{t('auth.retry')}</button>
      </div>
    )
  }
  return <p>{t('auth.signingIn')}</p>
}
