import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { QueryState } from '@/components/molecules/QueryState'
import type { EligibilityResponse } from '@/types/api'

export interface EligibilityPanelProps {
  eligibility: EligibilityResponse | null
  loading: boolean
  error: string | null
  onRetry?: () => void
}

/** Shows whether the team meets the tournament registration requirements. */
export function EligibilityPanel({ eligibility, loading, error, onRetry }: EligibilityPanelProps) {
  return (
    <Card title="Elegibilidad" description="Requisitos para inscribirse en un torneo.">
      <QueryState loading={loading} error={error} onRetry={onRetry} inline>
        {eligibility && (
          <>
            {eligibility.eligible ? (
              <Alert kind="success" title="El equipo cumple los requisitos">
                Puede inscribirse en el torneo vigente.
              </Alert>
            ) : (
              <Alert kind="warning" title="El equipo aún no cumple los requisitos">
                <ul className="mt-1 list-disc space-y-0.5 pl-5">
                  {eligibility.problems.map((problem) => (
                    <li key={problem}>{problem}</li>
                  ))}
                </ul>
              </Alert>
            )}
            <ul className="mt-3 space-y-1 text-xs text-stone-500">
              <li>Entre 7 y 12 integrantes.</li>
              <li>Sin dorsales repetidos.</li>
              <li>Todos los integrantes con perfil deportivo.</li>
              <li>Más de la mitad de integrantes de Sistemas, IA, Ciberseguridad o Estadística.</li>
            </ul>
          </>
        )}
      </QueryState>
    </Card>
  )
}
