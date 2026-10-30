import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { I18nextProvider } from 'react-i18next'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { ApiProvider, useApi } from '../api/ApiProvider'
import { AuthProvider } from '../auth/AuthProvider'
import { createI18n } from '../i18n'
import { fakeAuth, fakeFetch } from '../test/fakes'
import { ClinicChooser } from './ClinicChooser'
import { ClinicProvider, useClinic } from './ClinicProvider'

const clinics = [
  { id: 'clinic-a', name: 'Clínica A' },
  { id: 'clinic-b', name: 'Clínica B' },
]

function Probe() {
  const api = useApi()
  const { clinicId } = useClinic()
  return (
    <>
      <p data-testid="active">{clinicId ?? 'none'}</p>
      <button onClick={() => void api.GET('/api/v1/roles')}>call</button>
    </>
  )
}

function setup(me: unknown = clinics) {
  const fetch = fakeFetch({ '/api/v1/me/clinics': me, '/api/v1/roles': [] })
  render(
    <I18nextProvider i18n={createI18n({ strict: true })}>
      <AuthProvider service={fakeAuth('tok')}>
        <ClinicProvider>
          <ApiProvider baseUrl="http://api.test" fetch={fetch}>
            <MemoryRouter>
              <ClinicChooser>
                <Probe />
              </ClinicChooser>
            </MemoryRouter>
          </ApiProvider>
        </ClinicProvider>
      </AuthProvider>
    </I18nextProvider>,
  )
  return fetch
}

beforeEach(() => window.sessionStorage.clear())

describe('clinic choice', () => {
  it('asks the user to choose when there is more than one clinic', async () => {
    setup()
    expect(await screen.findByRole('button', { name: 'Clínica A' })).toBeInTheDocument()
    expect(screen.queryByTestId('active')).not.toBeInTheDocument()
  })

  it('uses the chosen clinic in the X-Clinic-Id header', async () => {
    const fetch = setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica B' }))
    await userEvent.click(await screen.findByRole('button', { name: 'call' }))
    await waitFor(() => expect(fetch.calls.at(-1)?.headers.get('X-Clinic-Id')).toBe('clinic-b'))
    expect(fetch.calls.at(-1)?.headers.get('Authorization')).toBe('Bearer tok')
  })

  it('selects the only clinic without asking', async () => {
    setup([clinics[0]])
    expect(await screen.findByTestId('active')).toHaveTextContent('clinic-a')
  })

  it('switches clinic and keeps using the new one', async () => {
    const fetch = setup()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica A' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Trocar de clínica' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica B' }))
    await userEvent.click(await screen.findByRole('button', { name: 'call' }))
    await waitFor(() => expect(fetch.calls.at(-1)?.headers.get('X-Clinic-Id')).toBe('clinic-b'))
  })

  it('forgets a stored clinic the user no longer belongs to', async () => {
    window.sessionStorage.setItem('clinic.active', 'clinic-gone')
    setup()
    expect(await screen.findByRole('button', { name: 'Clínica A' })).toBeInTheDocument()
    expect(window.sessionStorage.getItem('clinic.active')).toBeNull()
  })

  it('tells the user when there is no clinic', async () => {
    setup([])
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem acesso a nenhuma clínica')
  })

  it('tells the user when the clinics cannot be loaded', async () => {
    const fetch = fakeFetch({})
    render(
      <I18nextProvider i18n={createI18n({ strict: true })}>
        <AuthProvider service={fakeAuth('tok')}>
          <ClinicProvider>
            <ApiProvider baseUrl="http://api.test" fetch={fetch}>
              <MemoryRouter>
                <ClinicChooser>
                  <p />
                </ClinicChooser>
              </MemoryRouter>
            </ApiProvider>
          </ClinicProvider>
        </AuthProvider>
      </I18nextProvider>,
    )
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar as clínicas')
  })
})
