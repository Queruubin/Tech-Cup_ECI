import type { ReactNode } from 'react'
import { Link, Navigate, useParams } from 'react-router'
import { EmptyState } from '@/components/molecules/EmptyState'
import { QueryState } from '@/components/molecules/QueryState'
import { ToastViewport } from '@/components/molecules/ToastViewport'
import { useAuth } from '@/features/auth/hooks/useAuth'
import { formatDate } from '@/lib/format'
import { HistoryTab } from '../components/HistoryTab'
import { historyPath } from '../history'
import { usePastTournaments } from '../hooks/useTournaments'

function PublicShell({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen bg-stone-50">
      <header className="border-b border-stone-200 bg-white">
        <div className="mx-auto flex max-w-5xl items-center justify-between gap-3 px-4 py-3">
          <Link to="/history" className="flex items-center gap-2 text-ink">
            <img src="/logo.svg" alt="" className="h-8 w-auto" />
            <span className="font-semibold tracking-tight">
              TechCup <span className="text-brand-600">Fútbol</span>
            </span>
          </Link>
          <Link to="/login" className="text-sm font-medium text-brand-700 hover:underline">
            Iniciar sesión
          </Link>
        </div>
      </header>
      <main className="mx-auto max-w-5xl px-4 py-6">{children}</main>
      <ToastViewport />
    </div>
  )
}

function PastList() {
  const past = usePastTournaments()
  return (
    <>
      <h1 className="mb-1 text-xl font-semibold text-ink">Torneos anteriores</h1>
      <p className="mb-6 text-sm text-stone-500">Campeones, podio y estadísticas de los torneos finalizados.</p>
      <QueryState loading={past.loading} error={past.error} onRetry={past.refetch}>
        {(past.data ?? []).length === 0 ? (
          <EmptyState title="Aún no hay torneos finalizados" description="Cuando un torneo termine, aparecerá aquí." />
        ) : (
          <ul className="grid grid-cols-1 gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {(past.data ?? []).map((tournament) => (
              <li key={tournament.id}>
                <Link
                  to={`/history/${tournament.id}`}
                  className="flex flex-col gap-2 rounded-2xl border border-stone-200 bg-white p-4 shadow-sm transition hover:border-brand-300"
                >
                  <span className="truncate font-semibold text-ink">{tournament.name}</span>
                  <span className="text-xs text-stone-500">
                    {formatDate(tournament.startDate)} – {formatDate(tournament.endDate)}
                  </span>
                  <span className="text-sm text-stone-700">
                    <span className="text-xs font-semibold uppercase tracking-wide text-brand-700">Campeón</span>{' '}
                    {tournament.champion?.teamName ?? 'Sin campeón'}
                  </span>
                </Link>
              </li>
            ))}
          </ul>
        )}
      </QueryState>
    </>
  )
}

function PublicHistory({ tournamentId }: { tournamentId: number }) {
  return (
    <>
      <Link to="/history" className="mb-2 inline-block text-sm text-brand-700 hover:underline">
        ← Torneos anteriores
      </Link>
      <HistoryTab tournamentId={tournamentId} canManage={false} showName />
    </>
  )
}

/**
 * Public (no session) history of finished tournaments: `/history` lists them, `/history/:id` shows
 * one. Signed-in users are sent to the same views inside the app ("Torneos anteriores" and the
 * "Historial" tab), where staff also get the sanction and photo controls.
 */
export function PublicHistoryPage() {
  const params = useParams<{ id: string }>()
  const { isAuthenticated } = useAuth()
  const tournamentId = params.id && /^\d+$/.test(params.id) ? Number(params.id) : null

  if (isAuthenticated) {
    return <Navigate to={tournamentId === null ? '/tournaments?view=past' : historyPath(tournamentId)} replace />
  }
  return (
    <PublicShell>
      {params.id === undefined ? (
        <PastList />
      ) : tournamentId === null ? (
        <EmptyState title="Torneo no encontrado" description="El identificador del torneo no es válido." />
      ) : (
        <PublicHistory tournamentId={tournamentId} />
      )}
    </PublicShell>
  )
}
