import type { ReactNode } from 'react'
import { Button } from '@/components/atoms/Button'
import { Spinner } from '@/components/atoms/Spinner'
import { Alert } from './Alert'

export interface QueryStateProps {
  loading: boolean
  error: string | null
  onRetry?: () => void
  /** Rendered only when not loading and without error. */
  children: ReactNode
  /** Compact spinner instead of a full-height block. */
  inline?: boolean
}

/** Standard loading / error / content switch for pages backed by a query hook. */
export function QueryState({ loading, error, onRetry, children, inline }: QueryStateProps) {
  if (loading) {
    return (
      <div className={inline ? 'flex items-center gap-2 py-3 text-sm text-stone-500' : 'flex justify-center py-16'}>
        <Spinner size={inline ? 'sm' : 'lg'} />
        {inline && <span>Cargando…</span>}
      </div>
    )
  }
  if (error) {
    return (
      <Alert kind="error" title="No fue posible cargar la información">
        <p>{error}</p>
        {onRetry && (
          <Button variant="outline" size="sm" className="mt-3" onClick={onRetry}>
            Reintentar
          </Button>
        )}
      </Alert>
    )
  }
  return <>{children}</>
}
