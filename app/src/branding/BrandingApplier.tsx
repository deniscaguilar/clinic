import { useEffect } from 'react'
import { useSession } from '../session/SessionProvider'
import { applyBranding, clearBranding } from './applyBranding'

/** Aplica o branding da sessão enquanto a clínica estiver ativa e limpa ao sair dela. */
export function BrandingApplier() {
  const { session } = useSession()
  const branding = session.branding
  useEffect(() => {
    if (branding) applyBranding(document, branding)
    return () => clearBranding(document)
  }, [branding])
  return null
}
