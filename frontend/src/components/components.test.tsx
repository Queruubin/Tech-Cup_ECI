import { render, screen } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { useState } from 'react'
import { describe, expect, it, vi } from 'vitest'
import { Badge, StatusBadge } from './atoms/Badge'
import { Button } from './atoms/Button'
import { FileInput } from './atoms/FileInput'
import { Input } from './atoms/Input'
import { FormField } from './molecules/FormField'
import { Modal } from './molecules/Modal'

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

  it('clears the native input when the parent resets the value, so the same file can be picked again', async () => {
    const onChange = vi.fn()
    function Harness() {
      const [file, setFile] = useState<File | null>(null)
      return (
        <>
          <FileInput
            accept="application/pdf"
            value={file}
            onChange={(next) => {
              onChange(next)
              setFile(next)
            }}
          />
          <button type="button" onClick={() => setFile(null)}>
            Simular carga
          </button>
        </>
      )
    }
    render(<Harness />)
    const input = screen.getByLabelText('Elegir archivo') as HTMLInputElement
    const file = makeFile('reglamento.pdf', 'application/pdf', 512)

    await userEvent.upload(input, file)
    expect(input.files).toHaveLength(1)
    expect(screen.getByText('reglamento.pdf')).toBeInTheDocument()

    // The parent clears the value after a successful upload.
    await userEvent.click(screen.getByRole('button', { name: 'Simular carga' }))
    expect(input.value).toBe('')
    expect(screen.queryByText('reglamento.pdf')).not.toBeInTheDocument()

    await userEvent.upload(input, file)
    expect(onChange).toHaveBeenCalledTimes(2)
    expect(screen.getByText('reglamento.pdf')).toBeInTheDocument()
  })
})

describe('Modal', () => {
  function Harness() {
    const [open, setOpen] = useState(false)
    return (
      <>
        <button type="button" onClick={() => setOpen(true)}>
          Abrir
        </button>
        <Modal open={open} onClose={() => setOpen(false)} title="Diálogo de prueba">
          <label>
            Nota
            <input />
          </label>
        </Modal>
      </>
    )
  }

  it('moves focus into the dialog on open and restores it on close', async () => {
    const user = userEvent.setup()
    render(<Harness />)
    const trigger = screen.getByRole('button', { name: 'Abrir' })
    trigger.focus()
    expect(trigger).toHaveFocus()

    await user.click(trigger)
    const dialog = screen.getByRole('dialog', { name: 'Diálogo de prueba' })
    expect(dialog.contains(document.activeElement)).toBe(true)
    expect(screen.getByLabelText('Nota')).toHaveFocus()

    await user.keyboard('{Escape}')
    expect(screen.queryByRole('dialog')).not.toBeInTheDocument()
    expect(trigger).toHaveFocus()
  })

  it('falls back to the dialog element when there is nothing focusable inside', async () => {
    const user = userEvent.setup()
    function Empty() {
      const [open, setOpen] = useState(false)
      return (
        <>
          <button type="button" onClick={() => setOpen(true)}>
            Abrir
          </button>
          <Modal open={open} onClose={() => setOpen(false)} title="Sin controles">
            <p>Solo texto</p>
          </Modal>
        </>
      )
    }
    render(<Empty />)
    await user.click(screen.getByRole('button', { name: 'Abrir' }))
    expect(screen.getByRole('dialog', { name: 'Sin controles' })).toHaveFocus()
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
