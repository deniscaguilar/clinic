import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { BrowserRouter } from 'react-router'
import { App } from './App'
import { AppProviders } from './AppProviders'
import { createOidcAuthService } from './auth/oidc'
import { createI18n } from './i18n'

const i18n = createI18n()
document.title = i18n.t('app.name')

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <AppProviders i18n={i18n} auth={createOidcAuthService()}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </AppProviders>
  </StrictMode>,
)
