import { render, screen, waitFor } from '@testing-library/react'
import { MemoryRouter, Route, Routes } from 'react-router'
import { I18nextProvider } from 'react-i18next'
import { describe, expect, it } from 'vitest'
import { createI18n } from '../i18n'
import { fakeAuth } from '../test/fakes'
import { AuthCallback, AuthProvider, RequireAuth } from './AuthProvider'

function app(service: ReturnType<typeof fakeAuth>, route: string) {
  return render(
    <I18nextProvider i18n={createI18n({ strict: true })}>
      <AuthProvider service={service}>
        <MemoryRouter initialEntries={[route]}>
          <Routes>
            <Route path="/auth/callback" element={<AuthCallback />} />
            <Route
              path="/"
              element={
                <RequireAuth>
                  <p data-testid="private" />
                </RequireAuth>
              }
            />
          </Routes>
        </MemoryRouter>
      </AuthProvider>
    </I18nextProvider>,
  )
}

describe('login gate', () => {
  it('sends an anonymous visitor to the identity provider', async () => {
    const service = fakeAuth(null)
    app(service, '/')
    await waitFor(() => expect(service.login).toHaveBeenCalledTimes(1))
    expect(screen.queryByTestId('private')).not.toBeInTheDocument()
  })

  it('shows the private area to an authenticated user without redirecting', async () => {
    const service = fakeAuth('token')
    app(service, '/')
    expect(await screen.findByTestId('private')).toBeInTheDocument()
    expect(service.login).not.toHaveBeenCalled()
  })

  it('finishes the login on the callback and returns to the app', async () => {
    const service = fakeAuth(null)
    app(service, '/auth/callback')
    await waitFor(() => expect(service.completeLogin).toHaveBeenCalledTimes(1))
    expect(await screen.findByTestId('private')).toBeInTheDocument()
    expect(service.login).not.toHaveBeenCalled() // a sessão nova é reconhecida, sem novo redirecionamento
  })

  it('shows an error when the callback fails', async () => {
    const service = fakeAuth(null)
    service.completeLogin.mockRejectedValueOnce(new Error('boom'))
    app(service, '/auth/callback')
    expect(await screen.findByRole('alert')).toHaveTextContent('Não foi possível concluir o login')
  })
})
