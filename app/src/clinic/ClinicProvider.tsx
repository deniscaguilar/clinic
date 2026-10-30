import { createContext, useCallback, useContext, useMemo, useRef, useState, type ReactNode } from 'react'

const STORAGE_KEY = 'clinic.active'

interface ClinicContextValue {
  clinicId: string | null
  setClinicId(id: string | null): void
  /** Lê a clínica ativa no momento da chamada (o cliente da API usa isto a cada requisição). */
  getClinicId(): string | null
}

const ClinicContext = createContext<ClinicContextValue | null>(null)

export function useClinic(): ClinicContextValue {
  const value = useContext(ClinicContext)
  if (!value) throw new Error('useClinic requires a ClinicProvider')
  return value
}

/** A clínica ativa fica na sessionStorage: sobrevive ao recarregar a aba, não a uma sessão nova. */
export function ClinicProvider({ children }: { children: ReactNode }) {
  const [clinicId, setState] = useState<string | null>(() => window.sessionStorage.getItem(STORAGE_KEY))
  const current = useRef(clinicId)
  const setClinicId = useCallback((id: string | null) => {
    current.current = id
    if (id) window.sessionStorage.setItem(STORAGE_KEY, id)
    else window.sessionStorage.removeItem(STORAGE_KEY)
    setState(id)
  }, [])
  const getClinicId = useCallback(() => current.current, [])
  const value = useMemo(() => ({ clinicId, setClinicId, getClinicId }), [clinicId, setClinicId, getClinicId])
  return <ClinicContext.Provider value={value}>{children}</ClinicContext.Provider>
}
