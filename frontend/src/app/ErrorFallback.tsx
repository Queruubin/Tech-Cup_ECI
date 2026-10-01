import { Button } from '@/components/atoms/Button'

export interface ErrorFallbackProps {
  title: string
  description: string
  /** Offers a full reload; hidden for "not found" pages where retrying cannot help. */
  showRetry?: boolean
  /** Technical detail shown in a collapsible block. Callers pass it in development only. */
  details?: string | null
}

/**
 * Full-page, branded error screen shared by the router `errorElement` and the top-level error
 * boundary. It must not depend on the router (the boundary may render outside it), so navigation
 * uses full page loads, which also discard any broken in-memory state.
 */
export function ErrorFallback({ title, description, showRetry = true, details }: ErrorFallbackProps) {
  return (
    <div className="flex min-h-screen flex-col items-center justify-center bg-stone-50 px-4 py-10">
      <a href="/" className="mb-6 flex flex-col items-center gap-3 text-ink">
        <img src="/logo.svg" alt="" className="h-20 w-auto" />
        <span className="text-xl font-semibold tracking-tight">
          TechCup <span className="text-brand-600">Fútbol</span>
        </span>
      </a>
      <main
        role="alert"
        className="w-full max-w-md rounded-2xl border border-stone-200 bg-white p-6 text-center shadow-sm sm:p-8"
      >
        <h1 className="text-xl font-semibold text-ink">{title}</h1>
        <p className="mt-2 text-sm text-stone-500">{description}</p>
        <div className="mt-6 flex flex-col-reverse justify-center gap-2 sm:flex-row">
          <Button variant={showRetry ? 'outline' : 'primary'} onClick={() => window.location.assign('/')}>
            Ir al inicio
          </Button>
          {showRetry && <Button onClick={() => window.location.reload()}>Reintentar</Button>}
        </div>
        {details && (
          <details className="mt-6 text-left">
            <summary className="cursor-pointer text-xs font-medium text-stone-500">Detalles técnicos (solo en desarrollo)</summary>
            <pre className="mt-2 max-h-60 overflow-auto rounded-lg bg-stone-100 p-3 text-xs whitespace-pre-wrap break-words text-stone-700">
              {details}
            </pre>
          </details>
        )}
      </main>
    </div>
  )
}
