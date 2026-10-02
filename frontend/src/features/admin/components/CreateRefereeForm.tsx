import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { PasswordInput } from '@/components/atoms/PasswordInput'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { PASSWORD_RULE_HINT, validateNewPassword } from '@/features/auth/validation'
import { DOCUMENT_TYPE_LABELS, toOptions } from '@/lib/labels'
import { DOCUMENT_TYPES, type CreateRefereeRequest, type DocumentType } from '@/types/api'

export interface CreateRefereeFormProps {
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (payload: CreateRefereeRequest) => void
  onCancel: () => void
}

const DOCUMENT_OPTIONS = toOptions(DOCUMENT_TYPES, DOCUMENT_TYPE_LABELS)
const EMAIL_PATTERN = /^[^\s@]+@[^\s@]+\.[^\s@]+$/

type Errors = Partial<Record<keyof CreateRefereeRequest, string>>

export function CreateRefereeForm({ loading, error, fieldErrors, onSubmit, onCancel }: CreateRefereeFormProps) {
  const [fullName, setFullName] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [birthDate, setBirthDate] = useState('')
  const [documentType, setDocumentType] = useState<DocumentType | ''>('')
  const [documentNumber, setDocumentNumber] = useState('')
  const [errors, setErrors] = useState<Errors>({})

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const next: Errors = {}
    if (fullName.trim().length < 3) next.fullName = 'Ingrese el nombre completo.'
    if (!EMAIL_PATTERN.test(email.trim())) next.email = 'Ingrese un correo válido.'
    const passwordError = validateNewPassword(password)
    if (passwordError) next.password = passwordError
    if (!birthDate) next.birthDate = 'Ingrese la fecha de nacimiento.'
    if (!documentType) next.documentType = 'Seleccione el tipo de documento.'
    if (!documentNumber.trim()) next.documentNumber = 'Ingrese el número de documento.'
    setErrors(next)
    if (Object.keys(next).length > 0 || !documentType) return
    onSubmit({
      fullName: fullName.trim(),
      email: email.trim().toLowerCase(),
      password,
      birthDate,
      documentType,
      documentNumber: documentNumber.trim(),
    })
  }

  const errorFor = (key: keyof CreateRefereeRequest) => errors[key] ?? fieldErrors[key]

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
      {error && <Alert kind="error">{error}</Alert>}
      <div className="grid grid-cols-1 gap-4 sm:grid-cols-2">
        <FormField label="Nombre completo" required error={errorFor('fullName')} className="sm:col-span-2">
          <Input value={fullName} onChange={(event) => setFullName(event.target.value)} />
        </FormField>
        <FormField label="Correo electrónico" required error={errorFor('email')}>
          <Input type="email" value={email} onChange={(event) => setEmail(event.target.value)} />
        </FormField>
        <FormField label="Contraseña inicial" required hint={PASSWORD_RULE_HINT} error={errorFor('password')}>
          <PasswordInput autoComplete="new-password" value={password} onChange={(event) => setPassword(event.target.value)} />
        </FormField>
        <FormField label="Fecha de nacimiento" required error={errorFor('birthDate')}>
          <Input type="date" value={birthDate} onChange={(event) => setBirthDate(event.target.value)} />
        </FormField>
        <FormField label="Tipo de documento" required error={errorFor('documentType')}>
          <Select
            options={DOCUMENT_OPTIONS}
            placeholder="Seleccione"
            value={documentType}
            onChange={(event) => setDocumentType(event.target.value as DocumentType | '')}
          />
        </FormField>
        <FormField label="Número de documento" required error={errorFor('documentNumber')} className="sm:col-span-2">
          <Input value={documentNumber} onChange={(event) => setDocumentNumber(event.target.value)} />
        </FormField>
      </div>
      <div className="flex flex-col-reverse gap-2 sm:flex-row sm:justify-end">
        <Button type="button" variant="outline" onClick={onCancel} disabled={loading}>
          Cancelar
        </Button>
        <Button type="submit" loading={loading}>
          Crear árbitro
        </Button>
      </div>
    </form>
  )
}
