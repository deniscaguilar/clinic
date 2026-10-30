import { UserManager, WebStorageStateStore, type UserManagerSettings } from 'oidc-client-ts'
import { safeReturnTo, type AuthService } from './AuthService'

interface OidcConfig {
  authority: string
  clientId: string
  origin: string
}

/** Authorization Code com PKCE (a biblioteca aplica PKCE ao fluxo `code`). Tokens só na sessionStorage: somem ao fechar a aba. */
export function buildOidcSettings({ authority, clientId, origin }: OidcConfig): UserManagerSettings {
  const store = new WebStorageStateStore({ store: window.sessionStorage })
  return {
    authority,
    client_id: clientId,
    redirect_uri: `${origin}/auth/callback`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: 'openid profile email',
    userStore: store,
    stateStore: store,
    automaticSilentRenew: true,
  }
}

export class OidcAuthService implements AuthService {
  constructor(private readonly manager: UserManager) {}

  async login(returnTo: string): Promise<void> {
    await this.manager.signinRedirect({ state: returnTo })
  }

  async completeLogin(): Promise<string> {
    const user = await this.manager.signinRedirectCallback()
    return safeReturnTo(user.state)
  }

  async getAccessToken(): Promise<string | null> {
    const user = await this.manager.getUser()
    return user && !user.expired ? user.access_token : null
  }

  async logout(): Promise<void> {
    await this.manager.signoutRedirect()
  }
}

/** Configuração do ambiente (`VITE_OIDC_*`); os padrões apontam para o mock OIDC do Compose local. */
export function createOidcAuthService(): AuthService {
  const settings = buildOidcSettings({
    authority: import.meta.env.VITE_OIDC_AUTHORITY ?? 'http://localhost:8081/clinic',
    clientId: import.meta.env.VITE_OIDC_CLIENT_ID ?? 'clinic-app',
    origin: window.location.origin,
  })
  return new OidcAuthService(new UserManager(settings))
}
