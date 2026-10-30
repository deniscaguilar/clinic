import type { ReactNode } from 'react'
import { I18nextProvider } from 'react-i18next'
import type { i18n } from 'i18next'
import { ApiProvider } from './api/ApiProvider'
import { AuthProvider } from './auth/AuthProvider'
import type { AuthService } from './auth/AuthService'
import { ClinicProvider } from './clinic/ClinicProvider'

interface Props {
  i18n: i18n
  auth: AuthService
  api?: { baseUrl?: string; fetch?: (request: Request) => Promise<Response> }
  children: ReactNode
}

/** Composição dos provedores; o roteador fica de fora (BrowserRouter no app, MemoryRouter nos testes). */
export function AppProviders({ i18n, auth, api, children }: Props) {
  return (
    <I18nextProvider i18n={i18n}>
      <AuthProvider service={auth}>
        <ClinicProvider>
          <ApiProvider {...api}>{children}</ApiProvider>
        </ClinicProvider>
      </AuthProvider>
    </I18nextProvider>
  )
}
