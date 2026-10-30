import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { clinicFetch } from '../test/clinic'
import { problem } from '../test/fakes'
import { renderApp } from '../test/render'

beforeEach(() => window.sessionStorage.clear())

const P = 'pppppppp-0000-0000-0000-000000000001'
const item = (over: object = {}) => ({ id: 'n1', patientId: P, kind: 'CONSENT_ACCEPTED', channel: 'EMAIL', status: 'SENT', attempts: 1, nextAttemptAt: null, lastErrorCode: null, createdAt: '2026-10-30T15:00:00Z', ...over })
const page = (items: object[], total = items.length) => ({ items, page: 0, size: 20, total })
const lastUrl = (fetch: ReturnType<typeof clinicFetch>) => new URL(fetch.calls.at(-1)!.url)

describe('notifications', () => {
  it('lists the notifications with kind, channel, status and the time in the clinic time zone', async () => {
    renderApp(clinicFetch({ '/api/v1/notifications': page([item()]) }), '/notifications')
    const row = (await screen.findByText('Confirmação de consentimento')).closest('li')!
    expect(within(row).getByText('E-mail')).toBeInTheDocument()
    expect(within(row).getByText('Enviada')).toBeInTheDocument()
    expect(row).toHaveTextContent('12:00')
  })

  it('links the patient by id only', async () => {
    renderApp(clinicFetch({ '/api/v1/notifications': page([item()]) }), '/notifications')
    expect(await screen.findByRole('link', { name: 'Ver paciente' })).toHaveAttribute('href', `/patients/${P}`)
  })

  it('shows the failure code, the attempts and the next attempt of a pending retry', async () => {
    const pending = item({ id: 'n2', channel: 'WHATSAPP', status: 'PENDING', attempts: 2, lastErrorCode: 'TIMEOUT', nextAttemptAt: '2026-10-30T18:00:00Z' })
    renderApp(clinicFetch({ '/api/v1/notifications': page([pending]) }), '/notifications')
    const row = (await screen.findByText('Confirmação de consentimento')).closest('li')!
    expect(row).toHaveTextContent('Pendente')
    expect(row).toHaveTextContent('2 tentativas')
    expect(row).toHaveTextContent('TIMEOUT')
    expect(row).toHaveTextContent('próxima tentativa em 30/10/2026')
  })

  it('shows a notification blocked by a missing consent', async () => {
    renderApp(clinicFetch({ '/api/v1/notifications': page([item({ status: 'BLOCKED_NO_CONSENT', attempts: 0 })]) }), '/notifications')
    expect(await screen.findByText('Bloqueada: sem consentimento')).toBeInTheDocument()
  })

  it('sends the status and channel filters and goes back to the first page', async () => {
    const fetch = clinicFetch({ '/api/v1/notifications': page([item()], 45) })
    renderApp(fetch, '/notifications')
    await screen.findByText('Confirmação de consentimento')
    await userEvent.click(screen.getByRole('button', { name: 'Próxima' }))
    await waitFor(() => expect(lastUrl(fetch).searchParams.get('page')).toBe('1'))
    await userEvent.selectOptions(screen.getByLabelText('Situação'), 'FAILED')
    await userEvent.selectOptions(screen.getByLabelText('Canal'), 'WHATSAPP')
    await userEvent.click(screen.getByRole('button', { name: 'Filtrar' }))
    await waitFor(() => expect(lastUrl(fetch).searchParams.get('status')).toBe('FAILED'))
    expect(lastUrl(fetch).searchParams.get('channel')).toBe('WHATSAPP')
    expect(lastUrl(fetch).searchParams.get('page')).toBe('0')
  })

  it('does not send filters that were left empty', async () => {
    const fetch = clinicFetch({ '/api/v1/notifications': page([item()]) })
    renderApp(fetch, '/notifications')
    await screen.findByText('Confirmação de consentimento')
    expect(lastUrl(fetch).searchParams.has('status')).toBe(false)
    expect(lastUrl(fetch).searchParams.has('channel')).toBe(false)
  })

  it('has an empty state and disables the pagination at the edges', async () => {
    renderApp(clinicFetch({ '/api/v1/notifications': page([]) }), '/notifications')
    expect(await screen.findByText('Nenhuma notificação encontrada.')).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Anterior' })).toBeDisabled()
    expect(screen.getByRole('button', { name: 'Próxima' })).toBeDisabled()
  })

  it('shows PERMISSION_DENIED', async () => {
    renderApp(clinicFetch({ '/api/v1/notifications': () => problem('PERMISSION_DENIED', 403) }), '/notifications')
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para esta ação.')
  })
})
