import { useTranslation } from 'react-i18next'
import { Link } from 'react-router'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useLoad } from '../ui/hooks'

export function AdminTemplates() {
  const api = useApi()
  const { t } = useTranslation()
  const { load } = useLoad(async () => (await call(api.GET('/api/v1/admin/consent-templates'))) ?? [], [api])
  return (
    <section>
      <h1>{t('admin.templates.title')}</h1>
      <Link to="/admin/consent-templates/new">{t('admin.templates.new')}</Link>
      {load.state === 'loading' && <p>{t('common.loading')}</p>}
      {load.state === 'error' && <ProblemAlert problem={load.problem} />}
      {load.state === 'ready' && load.data.length === 0 && <p>{t('admin.templates.empty')}</p>}
      {load.state === 'ready' && load.data.length > 0 && (
        <ul>
          {load.data.map((template) => (
            <li key={template.id}>
              <Link to={`/admin/consent-templates/${template.id}`}>{template.name}</Link> {!template.active && <span>{t('admin.templates.inactive')}</span>}
            </li>
          ))}
        </ul>
      )}
    </section>
  )
}
