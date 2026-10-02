import { useState, type FormEvent } from 'react'
import { Button } from '@/components/atoms/Button'
import { Input } from '@/components/atoms/Input'
import { PasswordInput } from '@/components/atoms/PasswordInput'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'

export interface LoginFormProps {
  loading: boolean
  error: string | null
  fieldErrors: Record<string, string>
  onSubmit: (values: { email: string; password: string }) => void
}

export function LoginForm({ loading, error, fieldErrors, onSubmit }: LoginFormProps) {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [localErrors, setLocalErrors] = useState<{ email?: string; password?: string }>({})

  const handleSubmit = (event: FormEvent) => {
    event.preventDefault()
    const errors: { email?: string; password?: string } = {}
    if (!email.trim()) errors.email = 'Ingrese su correo.'
    if (!password) errors.password = 'Ingrese su contraseña.'
    setLocalErrors(errors)
    if (Object.keys(errors).length > 0) return
    onSubmit({ email: email.trim(), password })
  }

  return (
    <form onSubmit={handleSubmit} className="flex flex-col gap-4" noValidate>
      {error && <Alert kind="error">{error}</Alert>}
      <FormField label="Correo electrónico" required error={localErrors.email ?? fieldErrors.email}>
        <Input
          type="email"
          autoComplete="email"
          value={email}
          onChange={(event) => setEmail(event.target.value)}
          placeholder="nombre@escuelaing.edu.co"
        />
      </FormField>
      <FormField label="Contraseña" required error={localErrors.password ?? fieldErrors.password}>
        <PasswordInput
          autoComplete="current-password"
          value={password}
          onChange={(event) => setPassword(event.target.value)}
        />
      </FormField>
      <Button type="submit" fullWidth loading={loading} size="lg">
        Iniciar sesión
      </Button>
    </form>
  )
}
