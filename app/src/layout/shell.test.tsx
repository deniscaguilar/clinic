import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router'
import { beforeEach, describe, expect, it } from 'vitest'
import { App } from '../App'
import { AppProviders } from '../AppProviders'
import { ClinicChooser } from '../clinic/ClinicChooser'
import { useClinic } from '../clinic/ClinicProvider'
import { createI18n } from '../i18n'
import { SessionProvider, useSession } from '../session/SessionProvider'
import { fakeAuth, fakeFetch, jsonResponse } from '../test/fakes'

const brandings: Record<string, object> = {
  'clinic-a': { displayName: 'Clínica A', primaryColor: '#AA0000', secondaryColor: '#220000', onPrimary: '#FFFFFF', logoUrl: 'https://files.test/a.png', faviconUrl: null },
  'clinic-b': { displayName: 'Clínica B', primaryColor: '#0000BB', secondaryColor: '#000022', onPrimary: '#FFFFFF', logoUrl: null, faviconUrl: null },
}
let permissions: Record<string, string[]>
let sessionFails = false

function renderShell() {
  const fetch = fakeFetch({
    '/api/v1/me/clinics': [
      { id: 'clinic-a', name: 'Clínica A' },
      { id: 'clinic-b', name: 'Clínica B' },
    ],
    '/api/v1/session': (request: Request) => {
      if (sessionFails) return jsonResponse({ code: 'INTERNAL_ERROR' }, 500)
      const id = request.headers.get('X-Clinic-Id') ?? ''
      return jsonResponse({ user: { id: 'u', name: 'Maria', email: 'm@x.test' }, clinic: { id, name: id }, roles: [], permissions: permissions[id] ?? [], branding: brandings[id] })
    },
  })
  render(
    <AppProviders i18n={createI18n({ strict: true })} auth={fakeAuth('tok')} api={{ baseUrl: 'http://api.test', fetch }}>
      <MemoryRouter>
        <App />
      </MemoryRouter>
    </AppProviders>,
  )
  return fetch
}

const primary = () => document.documentElement.style.getPropertyValue('--color-primary')

beforeEach(() => {
  window.sessionStorage.clear()
  sessionFails = false
  document.documentElement.removeAttribute('style')
  permissions = { 'clinic-a': ['VIEW_PATIENTS', 'MANAGE_USERS'], 'clinic-b': ['VIEW_PATIENTS'] }
})

describe('branding by clinic (CA6)', () => {
  it('applies each clinic with its own colors and name', async () => {
    renderShell()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica A' }))
    await waitFor(() => expect(primary()).toBe('#AA0000'))
    expect(document.title).toBe('Clínica A')

    await userEvent.click(await screen.findByRole('button', { name: 'Trocar de clínica' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica B' }))
    await waitFor(() => expect(primary()).toBe('#0000BB'))
    expect(document.title).toBe('Clínica B')
  })

  it('shows the clinic logo when it has one, and no image when it does not', async () => {
    renderShell()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica A' }))
    expect(await screen.findByRole('img', { name: 'Clínica A' })).toHaveAttribute('src', 'https://files.test/a.png')
    await userEvent.click(await screen.findByRole('button', { name: 'Trocar de clínica' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica B' }))
    await screen.findByRole('navigation')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('does not render a logo whose address is not http(s)', async () => {
    brandings['clinic-a'] = { ...brandings['clinic-a'], logoUrl: 'javascript:alert(1)' }
    renderShell()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica A' }))
    await screen.findByRole('navigation')
    expect(screen.queryByRole('img')).not.toBeInTheDocument()
  })

  it('never renders the children with the session of another clinic while switching', async () => {
    const seen: string[] = []
    function Probe() {
      const { session } = useSession()
      const { clinicId } = useClinic()
      seen.push(`${session.clinic?.id}|${clinicId}`)
      return null
    }
    const fetch = fakeFetch({
      '/api/v1/me/clinics': [
        { id: 'clinic-a', name: 'Clínica A' },
        { id: 'clinic-b', name: 'Clínica B' },
      ],
      '/api/v1/session': (request: Request) => {
        const id = request.headers.get('X-Clinic-Id') ?? ''
        return jsonResponse({ clinic: { id, name: id }, permissions: [] })
      },
    })
    render(
      <AppProviders i18n={createI18n({ strict: true })} auth={fakeAuth('tok')} api={{ baseUrl: 'http://api.test', fetch }}>
        <ClinicChooser>
          <SessionProvider>
            <Probe />
          </SessionProvider>
        </ClinicChooser>
      </AppProviders>,
    )
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica A' }))
    await waitFor(() => expect(seen).toContain('clinic-a|clinic-a'))
    await userEvent.click(await screen.findByRole('button', { name: 'Trocar de clínica' }))
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica B' }))
    await waitFor(() => expect(seen).toContain('clinic-b|clinic-b'))
    expect(seen.every((entry) => entry.split('|')[0] === entry.split('|')[1])).toBe(true)
  })

  it('does not keep the previous clinic colors while the chooser is open', async () => {
    renderShell()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica A' }))
    await waitFor(() => expect(primary()).toBe('#AA0000'))
    await userEvent.click(await screen.findByRole('button', { name: 'Trocar de clínica' }))
    await waitFor(() => expect(primary()).toBe(''))
  })
})

describe('navigation by permission', () => {
  it('shows only the items the session allows', async () => {
    renderShell()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica A' }))
    const nav = await screen.findByRole('navigation', { name: 'Menu principal' })
    expect(nav).toHaveTextContent('Pacientes')
    expect(nav).toHaveTextContent('Usuários')
    expect(nav).not.toHaveTextContent('Auditoria')
  })

  it('hides an item the other clinic does not grant', async () => {
    renderShell()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica B' }))
    const nav = await screen.findByRole('navigation', { name: 'Menu principal' })
    expect(nav).toHaveTextContent('Pacientes')
    expect(nav).not.toHaveTextContent('Usuários')
  })

  it('shows an error when the session cannot be loaded', async () => {
    sessionFails = true
    renderShell()
    await userEvent.click(await screen.findByRole('button', { name: 'Clínica A' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível carregar a sessão')
  })
})
