import { useEffect, useState, type FormEvent } from 'react'
import { useTranslation } from 'react-i18next'
import { useApi } from '../api/ApiProvider'
import { call } from '../api/errors'
import { isHttpUrl } from '../branding/applyBranding'
import { useSession } from '../session/SessionProvider'
import { TextField } from '../ui/fields'
import { ProblemAlert } from '../ui/ProblemAlert'
import { useAction, useLoad } from '../ui/hooks'
import { useFormErrors } from '../ui/useFormErrors'
import { maxLength, required, type Rule } from '../ui/validation'

const hexColor: Rule = (value) => (/^#[0-9a-fA-F]{6}$/.test(value.trim()) ? null : { key: 'branding.colorFormat' })

export function BrandingPage() {
  const api = useApi()
  const { t } = useTranslation()
  const { refresh } = useSession()
  const { load, reload } = useLoad(() => call(api.GET('/api/v1/branding')), [api])
  const { busy, problem, run } = useAction()
  const { check, serverMessage } = useFormErrors()
  const [displayName, setDisplayName] = useState('')
  const [primaryColor, setPrimaryColor] = useState('')
  const [secondaryColor, setSecondaryColor] = useState('')
  const [submitted, setSubmitted] = useState(false)
  const [saved, setSaved] = useState(false)
  const loaded = load.state === 'ready' ? load.data : undefined
  useEffect(() => {
    if (!loaded) return
    setDisplayName(loaded.displayName ?? '')
    setPrimaryColor(loaded.primaryColor ?? '')
    setSecondaryColor(loaded.secondaryColor ?? '')
  }, [loaded])

  if (load.state === 'loading') return <p>{t('common.loading')}</p>
  if (load.state === 'error') return <ProblemAlert problem={load.problem} />
  const errors = {
    displayName: check(displayName, [required, maxLength(120)]),
    primaryColor: check(primaryColor, [required, hexColor]),
    secondaryColor: check(secondaryColor, [required, hexColor]),
  }
  const show = (field: keyof typeof errors) => (submitted ? errors[field] : undefined) ?? serverMessage(problem, field)

  async function save(event: FormEvent) {
    event.preventDefault()
    setSubmitted(true)
    setSaved(false)
    if (Object.values(errors).some(Boolean)) return
    const ok = await run(async () => {
      await call(api.PUT('/api/v1/branding', { body: { displayName: displayName.trim(), primaryColor: primaryColor.trim(), secondaryColor: secondaryColor.trim() } }))
      await refresh()
      reload()
    })
    setSaved(ok)
  }

  const upload = (kind: 'logo' | 'favicon', file: File | undefined) => {
    if (!file) return
    const form = new FormData()
    form.append('file', file)
    return run(async () => {
      await call(
        api.PUT(kind === 'logo' ? '/api/v1/branding/logo' : '/api/v1/branding/favicon', {
          body: { file: '' } as never,
          bodySerializer: () => form,
        } as never),
      )
      await refresh()
      reload()
    })
  }

  return (
    <section>
      <h1>{t('branding.title')}</h1>
      <form onSubmit={(e) => void save(e)} noValidate aria-label={t('branding.title')}>
        <TextField label={t('branding.displayName')} value={displayName} onChange={setDisplayName} error={show('displayName')} />
        <TextField label={t('branding.primaryColor')} value={primaryColor} onChange={setPrimaryColor} error={show('primaryColor')} />
        <TextField label={t('branding.secondaryColor')} value={secondaryColor} onChange={setSecondaryColor} error={show('secondaryColor')} />
        <p>{t('branding.onPrimary', { color: load.data?.onPrimary ?? '' })}</p>
        <ProblemAlert problem={problem} />
        <button type="submit" disabled={busy}>
          {t('common.save')}
        </button>
        {saved && <p>{t('branding.saved')}</p>}
      </form>
      <div>
        <label htmlFor="logo-file">{t('branding.logo')}</label>
        <input id="logo-file" type="file" accept="image/png,image/jpeg,image/svg+xml" onChange={(e) => void upload('logo', e.target.files?.[0])} />
        {isHttpUrl(load.data?.logoUrl) && <img src={load.data?.logoUrl ?? ''} alt={t('branding.logoAlt')} height={48} />}
      </div>
      <div>
        <label htmlFor="favicon-file">{t('branding.favicon')}</label>
        <input id="favicon-file" type="file" accept="image/png,image/x-icon,image/svg+xml" onChange={(e) => void upload('favicon', e.target.files?.[0])} />
      </div>
    </section>
  )
}
