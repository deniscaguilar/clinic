import { useTranslation } from 'react-i18next'
import { Link, useParams } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { useSession } from '../session/SessionProvider'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { formatCpf } from './cpf'

export function PatientDetailPage() {
  const api = useApi()
  const { t, i18n } = useTranslation()
  const { can } = useSession()
  const id = useParams().id ?? ''
  const { load, reload } = useLoad(
    async () => ({
      patient: await call(api.GET('/api/v1/patients/{id}', { params: { path: { id } } })),
      grants: (await call(api.GET('/api/v1/patients/{patientId}/consents', { params: { path: { patientId: id } } }))) ?? [],
      types: (await call(api.GET('/api/v1/consent-types', { params: { query: { active: true } } }))) ?? [],
    }),
    [api, id],
  )
  const { busy, problem, run } = useAction()

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const { patient, grants, types } = load.data
  const grantedTypes = new Set(grants.filter((g) => g.active).map((g) => g.typeId))
  const available = types.filter((type) => !grantedTypes.has(type.id))
  const format = (value?: string) => (value ? new Intl.DateTimeFormat(i18n.language, { dateStyle: 'short', timeStyle: 'short', timeZone: 'America/Sao_Paulo' }).format(new Date(value)) : '')
  const act = (action: () => Promise<unknown>) =>
    run(async () => {
      await action()
      reload()
    })

  return (
    <section>
      <h1>{patient?.fullName}</h1>
      {can('MANAGE_PATIENTS') && <Link to={`/patients/${id}/edit`}>{t('patients.editLink')}</Link>}
      <dl>
        <dt>{t('patients.cpf')}</dt>
        <dd>{formatCpf(patient?.cpf)}</dd>
        <dt>{t('patients.birthDate')}</dt>
        <dd>{patient?.birthDate}</dd>
        {patient?.email && (
          <>
            <dt>{t('patients.email')}</dt>
            <dd>{patient.email}</dd>
          </>
        )}
        {patient?.whatsapp && (
          <>
            <dt>{t('patients.whatsapp')}</dt>
            <dd>{patient.whatsapp}</dd>
          </>
        )}
      </dl>

      <h2>{t('patients.consents')}</h2>
      {grants.length === 0 && <p>{t('patients.noConsents')}</p>}
      <ul aria-label={t('patients.consents')}>
        {grants.map((grant) => (
          <li key={grant.id}>
            {grant.typeName} · {t('consentTypes.version', { number: grant.versionNumber ?? 0 })} · {t('patients.acceptedAt', { when: format(grant.acceptedAt) })}{' '}
            {grant.active ? (
              can('RECORD_CONSENT') && (
                <button disabled={busy} onClick={() => void act(() => call(api.POST('/api/v1/patients/{patientId}/consents/{grantId}/revoke', { params: { path: { patientId: id, grantId: grant.id! } } })))}>
                  {t('patients.revoke')}
                </button>
              )
            ) : (
              <span>{t('patients.revokedAt', { when: format(grant.revokedAt ?? undefined) })}</span>
            )}
          </li>
        ))}
      </ul>
      {can('RECORD_CONSENT') && available.length > 0 && (
        <div>
          <h3>{t('patients.recordConsent')}</h3>
          <ul>
            {available.map((type) => (
              <li key={type.id}>
                {type.name}
                <button disabled={busy} onClick={() => void act(() => call(api.POST('/api/v1/patients/{patientId}/consents', { params: { path: { patientId: id } }, body: { typeId: type.id! } })))}>
                  {t('patients.accept')}
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}
      <ProblemAlert problem={problem} />
    </section>
  )
}
