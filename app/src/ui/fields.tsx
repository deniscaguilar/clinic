import { useId, type ReactNode } from 'react'

interface Common {
  label: string
  error?: string | undefined
}

function Wrapper({ id, label, error, children }: Common & { id: string; children: ReactNode }) {
  return (
    <div>
      <label htmlFor={id}>{label}</label>
      {children}
      {error && <p id={`${id}-error`}>{error}</p>}
    </div>
  )
}

export function TextField({ label, error, value, onChange, type = 'text', multiline = false, readOnly = false }: Common & { value: string; onChange: (value: string) => void; type?: string; multiline?: boolean; readOnly?: boolean }) {
  const id = useId()
  const props = { id, value, 'aria-invalid': error ? true : undefined, 'aria-describedby': error ? `${id}-error` : undefined }
  return (
    <Wrapper id={id} label={label} error={error}>
      {multiline ? <textarea {...props} rows={8} readOnly={readOnly} onChange={(e) => onChange(e.target.value)} /> : <input {...props} type={type} readOnly={readOnly} onChange={(e) => onChange(e.target.value)} />}
    </Wrapper>
  )
}

export function CheckboxField({ label, checked, onChange }: { label: string; checked: boolean; onChange: (checked: boolean) => void }) {
  const id = useId()
  return (
    <div>
      <input id={id} type="checkbox" checked={checked} onChange={(e) => onChange(e.target.checked)} />
      <label htmlFor={id}>{label}</label>
    </div>
  )
}
