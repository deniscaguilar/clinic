import { useTranslation } from 'react-i18next'
import { Link, NavLink, Outlet } from 'react-router'

export function AdminLayout() {
  const { t } = useTranslation()
  return (
    <div>
      <header>
        <strong>{t('admin.title')}</strong>
        <Link to="/">{t('admin.backToApp')}</Link>
      </header>
      <nav aria-label={t('admin.menu')}>
        <NavLink to="/admin/clinics">{t('admin.clinics.title')}</NavLink>
        <NavLink to="/admin/consent-templates">{t('admin.templates.title')}</NavLink>
      </nav>
      <main>
        <Outlet />
      </main>
    </div>
  )
}
