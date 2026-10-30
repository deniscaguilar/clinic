import { useEffect, useState, type ReactNode } from 'react'
import { useTranslation } from 'react-i18next'
import { Navigate } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { useClinic } from './ClinicProvider'

interface ClinicOption {
  id: string
  name: string
}

type Load = { state: 'loading' } | { state: 'error' } | { state: 'ready'; clinics: ClinicOption[] }

/** Sem clínica: se a pessoa é `PLATFORM_ADMIN` (a listagem de clínicas responde), vai ao backoffice; senão, recado. */
function NoClinics() {
  const api = useApi()
  const { t } = useTranslation()
  const [admin, setAdmin] = useState<boolean | null>(null)
  useEffect(() => {
    let cancelled = false
    api
      .GET('/api/v1/admin/clinics')
      .then(({ data }) => !cancelled && setAdmin(!!data))
      .catch(() => !cancelled && setAdmin(false))
    return () => {
      cancelled = true
    }
  }, [api])
  if (admin === null) return <p>{t('common.loading')}</p>
  if (admin) return <Navigate to="/admin" replace />
  return <p role="alert">{t('clinic.empty')}</p>
}

/** Só mostra o conteúdo com uma clínica ativa: escolhe sozinha se houver uma só, senão pede ao usuário. */
export function ClinicChooser({ children }: { children: ReactNode }) {
  const api = useApi()
  const { t } = useTranslation()
  const { clinicId, setClinicId } = useClinic()
  const [load, setLoad] = useState<Load>({ state: 'loading' })

  useEffect(() => {
    let cancelled = false
    api
      .GET('/api/v1/me/clinics')
      .then(({ data }) => {
        if (cancelled) return
        if (!data) return setLoad({ state: 'error' })
        const clinics = data.flatMap((c) => (c.id && c.name ? [{ id: c.id, name: c.name }] : []))
        setLoad({ state: 'ready', clinics })
      })
      .catch(() => !cancelled && setLoad({ state: 'error' }))
    return () => {
      cancelled = true
    }
  }, [api])

  const clinics = load.state === 'ready' ? load.clinics : null
  useEffect(() => {
    if (!clinics) return
    if (clinicId && !clinics.some((c) => c.id === clinicId)) setClinicId(null)
    else if (!clinicId && clinics.length === 1) setClinicId(clinics[0]!.id)
  }, [clinics, clinicId, setClinicId])

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <p role="alert">{t('clinic.loadError')}</p>
  if (load.clinics.length === 0) return <NoClinics />
  if (!clinicId || !load.clinics.some((c) => c.id === clinicId)) {
    return (
      <section>
        <h1>{t('clinic.choose')}</h1>
        <ul>
          {load.clinics.map((c) => (
            <li key={c.id}>
              <button onClick={() => setClinicId(c.id)}>{c.name}</button>
            </li>
          ))}
        </ul>
      </section>
    )
  }
  return (
    <>
      {load.clinics.length > 1 && <button onClick={() => setClinicId(null)}>{t('clinic.switch')}</button>}
      {children}
    </>
  )
}
