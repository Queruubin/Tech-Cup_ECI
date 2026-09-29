import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { describe, expect, it, vi } from 'vitest'
import { Badge, StatusBadge } from './atoms/Badge'
import { Button } from './atoms/Button'
import { FileInput } from './atoms/FileInput'
import { Input } from './atoms/Input'
import { FormField } from './molecules/FormField'

describe('Button', () => {
  it('renders its label and handles clicks', async () => {
    const onClick = vi.fn()
    render(<Button onClick={onClick}>Guardar</Button>)
    const button = screen.getByRole('button', { name: 'Guardar' })
    expect(button).toHaveAttribute('type', 'button')
    await userEvent.click(button)
    expect(onClick).toHaveBeenCalledTimes(1)
  })

  it('applies variant classes', () => {
    render(<Button variant="danger">Eliminar</Button>)
    const className = screen.getByRole('button', { name: 'Eliminar' }).className
    expect(className).toContain('border-brand-600')
    expect(className).toContain('text-brand-700')
  })

  it('is disabled and shows a spinner while loading', () => {
    render(<Button loading>Enviando</Button>)
    const button = screen.getByRole('button', { name: /Enviando/ })
    expect(button).toBeDisabled()
    expect(button).toHaveAttribute('aria-busy', 'true')
    expect(screen.getByRole('status')).toBeInTheDocument()
  })
})

describe('Badge', () => {
  it('renders children with the requested tone', () => {
    render(<Badge tone="success">Aprobada</Badge>)
    const badge = screen.getByText('Aprobada')
    expect(badge.className).toContain('green')
  })

  it('StatusBadge translates enum values to Spanish labels', () => {
    render(
      <>
        <StatusBadge kind="tournament" value="IN_PROGRESS" />
        <StatusBadge kind="registration" value="UNDER_REVIEW" />
        <StatusBadge kind="role" value="REFEREE" />
      </>,
    )
    expect(screen.getByText('En progreso')).toBeInTheDocument()
    expect(screen.getByText('En revisión')).toBeInTheDocument()
    expect(screen.getByText('Árbitro')).toBeInTheDocument()
  })
})

describe('FileInput', () => {
  function makeFile(name: string, type: string, size: number): File {
    const created = new File(['x'], name, { type })
    // Avoid allocating real multi-megabyte buffers in tests.
    Object.defineProperty(created, 'size', { value: size })
    return created
  }

  it('rejects a file over maxBytes with an inline message and reports null', async () => {
    const onChange = vi.fn()
    render(<FileInput accept="image/png,image/jpeg,image/webp" maxBytes={5 * 1024 * 1024} value={null} onChange={onChange} />)

    await userEvent.upload(screen.getByLabelText('Elegir archivo'), makeFile('grande.png', 'image/png', 6 * 1024 * 1024))

    expect(onChange).toHaveBeenCalledWith(null)
    expect(screen.getByRole('alert')).toHaveTextContent('supera el tamaño máximo permitido (5.0 MB)')
  })

  it('rejects a file whose type is not in the accept list', async () => {
    const onChange = vi.fn()
    const user = userEvent.setup({ applyAccept: false })
    render(<FileInput accept="application/pdf" value={null} onChange={onChange} />)

    await user.upload(screen.getByLabelText('Elegir archivo'), makeFile('foto.png', 'image/png', 10))

    expect(onChange).toHaveBeenCalledWith(null)
    expect(screen.getByRole('alert')).toHaveTextContent('Tipo de archivo no permitido.')
  })

  it('passes a valid file through and clears any previous error', async () => {
    const onChange = vi.fn()
    render(<FileInput accept="application/pdf" maxBytes={1024} value={null} onChange={onChange} />)

    const valid = makeFile('reglamento.pdf', 'application/pdf', 512)
    await userEvent.upload(screen.getByLabelText('Elegir archivo'), valid)

    expect(onChange).toHaveBeenCalledWith(valid)
    expect(screen.queryByRole('alert')).not.toBeInTheDocument()
  })
})

describe('FormField', () => {
  it('links the label to the control and shows the hint', () => {
    render(
      <FormField label="Correo" hint="Use su correo institucional">
        <Input defaultValue="" />
      </FormField>,
    )
    const input = screen.getByLabelText('Correo')
    expect(input).toBeInTheDocument()
    expect(input).not.toHaveAttribute('aria-invalid')
    expect(screen.getByText('Use su correo institucional')).toBeInTheDocument()
  })

  it('shows the error, marks the control invalid and hides the hint', () => {
    render(
      <FormField label="Correo" hint="Ayuda" error="Correo inválido" required>
        <Input defaultValue="" />
      </FormField>,
    )
    const input = screen.getByLabelText(/Correo/)
    expect(input).toHaveAttribute('aria-invalid', 'true')
    expect(screen.getByRole('alert')).toHaveTextContent('Correo inválido')
    expect(screen.queryByText('Ayuda')).not.toBeInTheDocument()
    expect(input.getAttribute('aria-describedby')).toContain('-error')
  })
})
