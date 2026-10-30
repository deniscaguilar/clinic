import { screen, waitFor, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { beforeEach, describe, expect, it } from 'vitest'
import { bodyOf, fakeAuth, fakeFetch, jsonResponse, problem } from '../test/fakes'
import { renderApp } from '../test/render'

const A = 'aaaaaaaa-0000-0000-0000-000000000001'
const T = 'bbbbbbbb-0000-0000-0000-000000000001'
const clinics = [
  { id: A, name: 'Clínica Alfa', status: 'ACTIVE' },
  { id: 'c2', name: 'Clínica Beta', status: 'INACTIVE' },
]
const detail = (status = 'ACTIVE', adminStatus = 'PENDING') => ({
  id: A,
  name: 'Clínica Alfa',
  status,
  admins: [{ membershipId: 'm1', email: 'admin@x.test', name: 'Admin', status: adminStatus }],
})
const posts = (fetch: ReturnType<typeof fakeFetch>, method: string, path: string) => fetch.calls.filter((c) => c.method === method && new URL(c.url).pathname === path)

beforeEach(() => window.sessionStorage.clear())

describe('admin clinics', () => {
  it('lists the clinics with their status', async () => {
    renderApp(fakeFetch({ '/api/v1/admin/clinics': clinics }), '/admin/clinics')
    expect(await screen.findByRole('link', { name: 'Clínica Alfa' })).toHaveAttribute('href', `/admin/clinics/${A}`)
    expect(screen.getByText('Inativa')).toBeInTheDocument()
  })

  it('shows the message of the error code when the list is refused', async () => {
    renderApp(fakeFetch({ '/api/v1/admin/clinics': () => problem('PERMISSION_DENIED', 403) }), '/admin/clinics')
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem permissão para esta ação.')
  })

  it('validates the creation form before calling the API', async () => {
    const fetch = fakeFetch({})
    renderApp(fetch, '/admin/clinics/new')
    await userEvent.click(await screen.findByRole('button', { name: 'Criar clínica' }))
    expect(screen.getAllByText('Campo obrigatório.')).toHaveLength(3)
    await userEvent.type(screen.getByLabelText('E-mail do administrador'), 'sem-arroba')
    await userEvent.click(screen.getByRole('button', { name: 'Criar clínica' }))
    expect(screen.getByText('E-mail inválido.')).toBeInTheDocument()
    expect(posts(fetch, 'POST', '/api/v1/admin/clinics')).toHaveLength(0)
  })

  it('creates the clinic and opens its page', async () => {
    const fetch = fakeFetch({
      'POST /api/v1/admin/clinics': jsonResponse({ id: A, firstAdmin: { membershipId: 'm1', status: 'PENDING' } }, 201),
      [`/api/v1/admin/clinics/${A}`]: detail(),
    })
    renderApp(fetch, '/admin/clinics/new')
    await userEvent.type(await screen.findByLabelText('Nome da clínica'), '  Clínica Alfa ')
    await userEvent.type(screen.getByLabelText('E-mail do administrador'), 'admin@x.test')
    await userEvent.type(screen.getByLabelText('Nome do administrador'), 'Admin')
    await userEvent.click(screen.getByRole('button', { name: 'Criar clínica' }))
    expect(await screen.findByRole('heading', { name: 'Clínica Alfa' })).toBeInTheDocument()
    expect(await bodyOf(posts(fetch, 'POST', '/api/v1/admin/clinics')[0]!)).toEqual({ name: 'Clínica Alfa', adminEmail: 'admin@x.test', adminName: 'Admin' })
  })

  it('shows the server error by code and the server field issues', async () => {
    const fetch = fakeFetch({ 'POST /api/v1/admin/clinics': () => problem('IDENTITY_PROVIDER_UNAVAILABLE', 502) })
    renderApp(fetch, '/admin/clinics/new')
    await userEvent.type(await screen.findByLabelText('Nome da clínica'), 'X')
    await userEvent.type(screen.getByLabelText('E-mail do administrador'), 'a@b.co')
    await userEvent.type(screen.getByLabelText('Nome do administrador'), 'Y')
    await userEvent.click(screen.getByRole('button', { name: 'Criar clínica' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('O provedor de identidade está indisponível')
  })

  it('maps a server validation issue to its field', async () => {
    const fetch = fakeFetch({ 'POST /api/v1/admin/clinics': () => problem('VALIDATION_FAILED', 400, [{ field: 'adminEmail', rule: 'Size' }]) })
    renderApp(fetch, '/admin/clinics/new')
    await userEvent.type(await screen.findByLabelText('Nome da clínica'), 'X')
    await userEvent.type(screen.getByLabelText('E-mail do administrador'), 'a@b.co')
    await userEvent.type(screen.getByLabelText('Nome do administrador'), 'Y')
    await userEvent.click(screen.getByRole('button', { name: 'Criar clínica' }))
    expect(await screen.findByText('Tamanho inválido.')).toBeInTheDocument()
    expect(screen.getByLabelText('E-mail do administrador')).toHaveAttribute('aria-invalid', 'true')
  })

  it('renames the clinic', async () => {
    const fetch = fakeFetch({ [`/api/v1/admin/clinics/${A}`]: detail(), [`PATCH /api/v1/admin/clinics/${A}`]: new Response(null, { status: 204 }) })
    renderApp(fetch, `/admin/clinics/${A}`)
    const input = await screen.findByDisplayValue('Clínica Alfa')
    await userEvent.clear(input)
    await userEvent.type(input, 'Novo Nome')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Nome salvo.')).toBeInTheDocument()
    expect(await bodyOf(posts(fetch, 'PATCH', `/api/v1/admin/clinics/${A}`)[0]!)).toEqual({ name: 'Novo Nome' })
  })

  it('does not rename to a blank name', async () => {
    const fetch = fakeFetch({ [`/api/v1/admin/clinics/${A}`]: detail() })
    renderApp(fetch, `/admin/clinics/${A}`)
    await screen.findByDisplayValue('Clínica Alfa')
    await userEvent.clear(screen.getByLabelText('Nome da clínica'))
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(screen.getByText('Campo obrigatório.')).toBeInTheDocument()
    expect(posts(fetch, 'PATCH', `/api/v1/admin/clinics/${A}`)).toHaveLength(0)
  })

  it('deactivates an active clinic and offers to activate an inactive one', async () => {
    let status = 'ACTIVE'
    const fetch = fakeFetch({
      [`/api/v1/admin/clinics/${A}`]: () => jsonResponse(detail(status)),
      [`POST /api/v1/admin/clinics/${A}/deactivate`]: () => {
        status = 'INACTIVE'
        return new Response(null, { status: 204 })
      },
    })
    renderApp(fetch, `/admin/clinics/${A}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Desativar clínica' }))
    expect(await screen.findByRole('button', { name: 'Ativar clínica' })).toBeInTheDocument()
    expect(posts(fetch, 'POST', `/api/v1/admin/clinics/${A}/deactivate`)).toHaveLength(1)
  })

  it('resends the invitation only for a pending administrator', async () => {
    const fetch = fakeFetch({ [`/api/v1/admin/clinics/${A}`]: detail('ACTIVE', 'PENDING'), [`POST /api/v1/admin/clinics/${A}/admins/m1/resend-invitation`]: { status: 'ACTIVE' } })
    renderApp(fetch, `/admin/clinics/${A}`)
    await userEvent.click(await screen.findByRole('button', { name: 'Reenviar convite' }))
    await waitFor(() => expect(posts(fetch, 'POST', `/api/v1/admin/clinics/${A}/admins/m1/resend-invitation`)).toHaveLength(1))
  })

  it('has no resend button for an active administrator', async () => {
    renderApp(fakeFetch({ [`/api/v1/admin/clinics/${A}`]: detail('ACTIVE', 'ACTIVE') }), `/admin/clinics/${A}`)
    await screen.findByText('Admin (admin@x.test)', { exact: false })
    expect(screen.queryByRole('button', { name: 'Reenviar convite' })).not.toBeInTheDocument()
  })

  it('shows CLINIC_NOT_FOUND', async () => {
    renderApp(fakeFetch({ [`/api/v1/admin/clinics/${A}`]: () => problem('CLINIC_NOT_FOUND', 404) }), `/admin/clinics/${A}`)
    expect(await screen.findByRole('alert')).toHaveTextContent('Clínica não encontrada.')
  })
})

describe('admin consent templates', () => {
  const template = { id: T, name: 'LGPD', text: 'Texto', requiredOnSignup: true, controlsMessaging: false, active: true }

  it('lists the templates', async () => {
    renderApp(fakeFetch({ '/api/v1/admin/consent-templates': [template, { ...template, id: 't2', name: 'Imagem', active: false }] }), '/admin/consent-templates')
    expect(await screen.findByRole('link', { name: 'LGPD' })).toBeInTheDocument()
    expect(screen.getByText('(inativo)')).toBeInTheDocument()
  })

  it('validates name and text, including the text size limit', async () => {
    const fetch = fakeFetch({})
    renderApp(fetch, '/admin/consent-templates/new')
    await userEvent.click(await screen.findByRole('button', { name: 'Salvar' }))
    expect(screen.getAllByText('Campo obrigatório.')).toHaveLength(2)
    await userEvent.type(screen.getByLabelText('Nome'), 'N')
    await userEvent.click(screen.getByLabelText('Texto'))
    await userEvent.paste('x'.repeat(20001))
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(screen.getByText('Texto longo demais (máximo de 20000 caracteres).')).toBeInTheDocument()
    expect(posts(fetch, 'POST', '/api/v1/admin/consent-templates')).toHaveLength(0)
  })

  it('creates a template with its options', async () => {
    const fetch = fakeFetch({ 'POST /api/v1/admin/consent-templates': jsonResponse(template, 201), [`/api/v1/admin/consent-templates/${T}`]: template })
    renderApp(fetch, '/admin/consent-templates/new')
    await userEvent.type(await screen.findByLabelText('Nome'), 'LGPD')
    await userEvent.type(screen.getByLabelText('Texto'), 'Texto')
    await userEvent.click(screen.getByLabelText('Obrigatório no cadastro'))
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('heading', { name: 'Editar modelo' })).toBeInTheDocument()
    expect(await bodyOf(posts(fetch, 'POST', '/api/v1/admin/consent-templates')[0]!)).toEqual({ name: 'LGPD', text: 'Texto', requiredOnSignup: true, controlsMessaging: false })
  })

  it('edits a template, sending the active flag', async () => {
    const fetch = fakeFetch({ [`/api/v1/admin/consent-templates/${T}`]: template, [`PUT /api/v1/admin/consent-templates/${T}`]: jsonResponse(template) })
    renderApp(fetch, `/admin/consent-templates/${T}`)
    expect(await screen.findByDisplayValue('LGPD')).toBeInTheDocument()
    await userEvent.click(screen.getByLabelText('Ativo'))
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByText('Modelo salvo.')).toBeInTheDocument()
    expect(await bodyOf(posts(fetch, 'PUT', `/api/v1/admin/consent-templates/${T}`)[0]!)).toEqual({ name: 'LGPD', text: 'Texto', requiredOnSignup: true, controlsMessaging: false, active: false })
  })

  it('shows CONSENT_TEMPLATE_NAME_TAKEN', async () => {
    renderApp(fakeFetch({ 'POST /api/v1/admin/consent-templates': () => problem('CONSENT_TEMPLATE_NAME_TAKEN', 409) }), '/admin/consent-templates/new')
    await userEvent.type(await screen.findByLabelText('Nome'), 'LGPD')
    await userEvent.type(screen.getByLabelText('Texto'), 'Texto')
    await userEvent.click(screen.getByRole('button', { name: 'Salvar' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Já existe um modelo de consentimento com este nome.')
  })
})

describe('platform admin without a clinic', () => {
  it('goes to the backoffice when the clinic list is empty and the user is a platform admin', async () => {
    renderApp(fakeFetch({ '/api/v1/me/clinics': [], '/api/v1/admin/clinics': clinics }), '/')
    expect(await screen.findByRole('heading', { name: 'Clínicas' })).toBeInTheDocument()
    expect(screen.getByRole('navigation', { name: 'Menu da administração' })).toBeInTheDocument()
  })

  it('tells a regular user without a clinic to contact the administrator', async () => {
    renderApp(fakeFetch({ '/api/v1/me/clinics': [], '/api/v1/admin/clinics': () => problem('PERMISSION_DENIED', 403) }), '/')
    expect(await screen.findByRole('alert')).toHaveTextContent('Você não tem acesso a nenhuma clínica')
  })

  it('does not require a clinic to open the backoffice', async () => {
    const fetch = fakeFetch({ '/api/v1/admin/clinics': clinics })
    renderApp(fetch, '/admin/clinics', fakeAuth('tok'))
    await screen.findByRole('link', { name: 'Clínica Alfa' })
    expect(fetch.calls.some((c) => new URL(c.url).pathname === '/api/v1/me/clinics')).toBe(false)
    expect(within(screen.getByRole('banner')).getByText('Administração da plataforma')).toBeInTheDocument()
  })
})
