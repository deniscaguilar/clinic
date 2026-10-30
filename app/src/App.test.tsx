import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { App } from './App'
import { AppProviders } from './AppProviders'
import { createI18n } from './i18n'
import { fakeAuth, fakeFetch } from './test/fakes'

beforeEach(() => window.sessionStorage.clear())

function renderApp(auth = fakeAuth('tok')) {
  const fetch = fakeFetch({
    '/api/v1/me/clinics': [{ id: 'clinic-a', name: 'Clínica A' }],
    '/api/v1/session': { user: {}, clinic: { id: 'clinic-a', name: 'Clínica A' }, roles: [], permissions: [], branding: { displayName: 'Clínica Aurora', primaryColor: '#112233', secondaryColor: '#223344', onPrimary: '#FFFFFF' } },
  })
  render(
    <AppProviders i18n={createI18n({ strict: true })} auth={auth} api={{ baseUrl: 'http://api.test', fetch }}>
      <MemoryRouter>
        <App />
      </MemoryRouter>
    </AppProviders>,
  )
  return auth
}

describe('app shell', () => {
  it('shows the shell after login and clinic choice', async () => {
    renderApp()
    expect(await screen.findByText('Clínica Aurora')).toBeInTheDocument()
  })

  it('signs out', async () => {
    const auth = renderApp()
    await userEvent.click(await screen.findByRole('button', { name: 'Sair' }))
    expect(auth.logout).toHaveBeenCalledTimes(1)
  })
})
