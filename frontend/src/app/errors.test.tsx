import { render, screen } from '@testing-library/react'
import { RouterProvider, createMemoryRouter } from 'react-router'
import { afterEach, describe, expect, it, vi } from 'vitest'
import { ApiError } from '@/lib/api'
import { useUiStore } from '@/store/ui.store'
import { AppErrorBoundary } from './AppErrorBoundary'
import { devErrorDetails } from './errorCopy'
import { RouteErrorPage } from './RouteErrorPage'
import { UNHANDLED_ERROR_MESSAGE, reportUnhandledRejection } from './unhandledRejection'

function Boom(): never {
  throw new Error('kaboom')
}

/** Silences the expected render error: React logs it and jsdom reports it as an uncaught window error. */
function silenceExpectedErrors() {
  vi.spyOn(console, 'error').mockImplementation(() => {})
  vi.spyOn(console, 'warn').mockImplementation(() => {})
  const swallow = (event: ErrorEvent) => event.preventDefault()
  window.addEventListener('error', swallow)
  return () => window.removeEventListener('error', swallow)
}

function renderRoutes(initialPath: string) {
  const router = createMemoryRouter(
    [
      {
        errorElement: <RouteErrorPage />,
        children: [
          { path: '/', element: <p>Inicio</p> },
          { path: '/boom', element: <Boom /> },
          {
            path: '/missing',
            loader: () => {
              throw new Response('Not found', { status: 404 })
            },
            element: <p>Nunca</p>,
          },
        ],
      },
    ],
    { initialEntries: [initialPath] },
  )
  return render(<RouterProvider router={router} />)
}

describe('RouteErrorPage', () => {
  afterEach(() => vi.restoreAllMocks())

  it('shows "Página no encontrada" for a 404 route response, without a retry button', async () => {
    renderRoutes('/missing')
    expect(await screen.findByRole('heading', { name: 'Página no encontrada' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ir al inicio' })).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Reintentar' })).not.toBeInTheDocument()
  })

  it('shows "Página no encontrada" when no route matches', async () => {
    renderRoutes('/does-not-exist')
    expect(await screen.findByRole('heading', { name: 'Página no encontrada' })).toBeInTheDocument()
  })

  it('shows the generic fallback with retry/home buttons for a thrown render error', async () => {
    const restore = silenceExpectedErrors()
    renderRoutes('/boom')
    expect(await screen.findByRole('heading', { name: 'Algo salió mal' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ir al inicio' })).toBeInTheDocument()
    // Vitest runs with import.meta.env.DEV = true, so the technical detail is available (collapsed).
    expect(screen.getByText('kaboom')).toBeInTheDocument()
    restore()
  })
})

describe('AppErrorBoundary', () => {
  afterEach(() => vi.restoreAllMocks())

  it('renders its children when nothing throws', () => {
    render(
      <AppErrorBoundary>
        <p>Contenido</p>
      </AppErrorBoundary>,
    )
    expect(screen.getByText('Contenido')).toBeInTheDocument()
  })

  it('renders the branded fallback when a child throws', () => {
    const restore = silenceExpectedErrors()
    render(
      <AppErrorBoundary>
        <Boom />
      </AppErrorBoundary>,
    )
    restore()
    expect(screen.getByRole('heading', { name: 'Algo salió mal' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Reintentar' })).toBeInTheDocument()
    expect(screen.getByRole('button', { name: 'Ir al inicio' })).toBeInTheDocument()
  })
})

describe('devErrorDetails', () => {
  it('exposes the message only in development', () => {
    expect(devErrorDetails(new Error('detalle'), true)).toBe('detalle')
    expect(devErrorDetails(new Error('detalle'), false)).toBeNull()
    expect(devErrorDetails('texto', true)).toBe('texto')
    expect(devErrorDetails(null, true)).toBeNull()
  })
})

describe('reportUnhandledRejection', () => {
  afterEach(() => useUiStore.getState().clearToasts())

  it('shows a generic toast for non-API errors, hiding technical text', () => {
    expect(reportUnhandledRejection(new TypeError('x is undefined'), false)).toBe(UNHANDLED_ERROR_MESSAGE)
    expect(useUiStore.getState().toasts).toMatchObject([{ kind: 'error', message: UNHANDLED_ERROR_MESSAGE }])
  })

  it('keeps the user-facing message of API errors', () => {
    const error = new ApiError({ status: 409, error: 'Conflict', message: 'Reabra el partido siguiente primero.' })
    expect(reportUnhandledRejection(error, false)).toBe('Reabra el partido siguiente primero.')
  })

  it('ignores aborted requests', () => {
    expect(reportUnhandledRejection(new DOMException('aborted', 'AbortError'), false)).toBeNull()
    expect(useUiStore.getState().toasts).toHaveLength(0)
  })
})
