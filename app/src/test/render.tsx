import { render } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { App } from '../App'
import { AppProviders } from '../AppProviders'
import { createI18n } from '../i18n'
import { fakeAuth, fakeFetch } from './fakes'

type Fetch = ReturnType<typeof fakeFetch>

/** O app inteiro (provedores, roteador em memória e `App`) sobre um `fetch` falso. */
export function renderApp(fetch: Fetch, route = '/', auth = fakeAuth('tok')) {
  render(
    <AppProviders i18n={createI18n({ strict: true })} auth={auth} api={{ baseUrl: 'http://api.test', fetch }}>
      <MemoryRouter initialEntries={[route]}>
        <App />
      </MemoryRouter>
    </AppProviders>,
  )
  return auth
}
