import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useLoad } from '../ui/hooks'

export function AdminClinics() {
  const api = useApi()
  const { t } = useTranslation()
  const { load } = useLoad(async () => (await call(api.GET('/api/v1/admin/clinics'))) ?? [], [api])
  return (
    <section>
      <h1>{t('admin.clinics.title')}</h1>
      <Link to="/admin/clinics/new">{t('admin.clinics.new')}</Link>
      {load.state === 'loading' && <p>{t('common.loading')}</p>}
      {load.state === 'error' && <ProblemAlert problem={load.problem} />}
      {load.state === 'ready' && load.data.length === 0 && <p>{t('admin.clinics.empty')}</p>}
      {load.state === 'ready' && load.data.length > 0 && (
        <ul>
          {load.data.map((clinic) => (
            <li key={clinic.id}>
              <Link to={`/admin/clinics/${clinic.id}`}>{clinic.name}</Link> <span>{t(`admin.status.${clinic.status}`)}</span>
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
