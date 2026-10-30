import { useTranslation } from 'react-i18next'
import { NavLink, Outlet } from 'react-router'
import { useAuth } from '../auth/AuthProvider'
import { isHttpUrl } from '../branding/applyBranding'
import { useSession } from '../session/SessionProvider'
import { NAV_ITEMS } from './navigation'

export function Shell() {
  const { t } = useTranslation()
  const { service } = useAuth()
  const { session, can } = useSession()
  const name = session.branding?.displayName ?? t('app.name')
  const logo = session.branding?.logoUrl
  return (
    <div>
      <header>
        {isHttpUrl(logo) && <img src={logo} alt={name} height={32} />}
        <strong>{name}</strong>
        <button onClick={() => void service.logout()}>{t('auth.logout')}</button>
      </header>
      <nav aria-label={t('nav.label')}>
        <ul>
          {NAV_ITEMS.filter((item) => can(item.permission)).map((item) => (
            <li key={item.path}>
              <NavLink to={item.path}>{t(item.labelKey)}</NavLink>
            </li>
          ))}
        </ul>
      </nav>
      <main>
        <Outlet />
      </main>
    </div>
  )
}
