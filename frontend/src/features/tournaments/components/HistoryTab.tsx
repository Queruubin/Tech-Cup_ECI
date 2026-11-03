import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Alert } from '@/components/molecules/Alert'
import { Card } from '@/components/molecules/Card'
import { EmptyState } from '@/components/molecules/EmptyState'
import { QueryState } from '@/components/molecules/QueryState'
import { cn } from '@/lib/cn'
import type { PodiumEntry } from '@/types/api'
import { goalsPerMatch, podiumLabel, podiumSourceHint } from '../history'
import { useTournamentHistory } from '../hooks/useTournaments'
import { PodiumPhotoEditor, PodiumPhotoViewer } from './PodiumPhotoModal'
import { SanctionedTeamsCard } from './SanctionedTeamsCard'

export interface HistoryTabProps {
  tournamentId: number
  /** ADMIN / ORGANIZER: sanctioned teams and podium photos are managed from here. */
  canManage: boolean
  /** The tournament was just finished from "Gestión": staff get a hint about what they can add. */
  justFinished?: boolean
  /** Shows the tournament name as the page title (public page, outside the tournament detail). */
  showName?: boolean
}

const PODIUM_STYLES: Record<number, string> = {
  1: 'border-brand-300 bg-brand-50',
  2: 'border-stone-300 bg-stone-50',
  3: 'border-stone-200 bg-white',
}

function PodiumCard({
  entry,
  canManage,
  onOpenPhoto,
  onEditPhoto,
}: {
  entry: PodiumEntry
  canManage: boolean
  onOpenPhoto: () => void
  onEditPhoto: () => void
}) {
  const hasPhoto = entry.photoFileId !== null
  return (
    <div
      className={cn('flex flex-col gap-2 rounded-2xl border p-4', PODIUM_STYLES[entry.position] ?? 'border-stone-200')}
      data-testid={`podium-${entry.position}`}
    >
      <p className="text-xs font-semibold uppercase tracking-wide text-brand-700">{podiumLabel(entry.position)}</p>
      {hasPhoto ? (
        <button
          type="button"
          onClick={onOpenPhoto}
          className="text-left text-lg font-semibold text-ink underline decoration-brand-300 underline-offset-4 hover:text-brand-700"
        >
          {entry.teamName}
          <span className="ml-2 align-middle text-xs font-normal text-brand-700 no-underline">Ver foto</span>
        </button>
      ) : (
        <p className="text-lg font-semibold text-ink">{entry.teamName}</p>
      )}
      <p className="text-xs text-stone-600">
        {entry.played} PJ · {entry.points} pts · DG {entry.goalDifference > 0 ? `+${entry.goalDifference}` : entry.goalDifference} ·{' '}
        {entry.goalsFor} GF
      </p>
      {canManage && (
        <div>
          <Button size="sm" variant="outline" onClick={onEditPhoto}>
            {hasPhoto ? 'Cambiar foto' : 'Agregar foto'}
          </Button>
        </div>
      )}
    </div>
  )
}

/**
 * History of a FINISHED tournament (public): podium (champion, runner-up, 3rd place), best
 * defence, top scorers and sanctioned teams. Staff manage the sanctioned teams and the optional
 * podium photos inline; nothing here is mandatory.
 */
export function HistoryTab({ tournamentId, canManage, justFinished = false, showName = false }: HistoryTabProps) {
  const query = useTournamentHistory(tournamentId)
  const history = query.data
  const [viewing, setViewing] = useState<PodiumEntry | null>(null)
  const [editing, setEditing] = useState<PodiumEntry | null>(null)
  const reload = () => void query.refetch()

  return (
    <QueryState loading={query.loading} error={query.error} onRetry={query.refetch}>
      {history && (
        <div className="flex flex-col gap-6">
          {showName && <h1 className="text-xl font-semibold text-ink">{history.name}</h1>}
          {justFinished && canManage && (
            <Alert kind="success" title="Torneo finalizado">
              Si corresponde, agregue ahora los equipos sancionados y las fotos del podio. Es opcional: también puede
              hacerlo más tarde desde esta pestaña «Historial».
            </Alert>
          )}

          <Card title="Podio" description={podiumSourceHint(history.podiumSource) ?? undefined}>
            {history.podium.length === 0 ? (
              <EmptyState title="Sin podio" description="El torneo terminó sin partidos jugados." />
            ) : (
              <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
                {history.podium.map((entry) => (
                  <PodiumCard
                    key={entry.position}
                    entry={entry}
                    canManage={canManage}
                    onOpenPhoto={() => setViewing(entry)}
                    onEditPhoto={() => setEditing(entry)}
                  />
                ))}
              </div>
            )}
            {history.thirdPlaceCandidates.length > 1 && (
              <div className="mt-4 overflow-x-auto">
                <table className="w-full text-left text-xs text-stone-600">
                  <caption className="mb-1 text-left font-medium text-stone-700">Semifinalistas eliminados</caption>
                  <thead>
                    <tr className="text-stone-500">
                      <th className="py-1 pr-3 font-medium">Equipo</th>
                      <th className="py-1 pr-3 font-medium">PJ</th>
                      <th className="py-1 pr-3 font-medium">Pts</th>
                      <th className="py-1 pr-3 font-medium">DG</th>
                      <th className="py-1 font-medium">GF</th>
                    </tr>
                  </thead>
                  <tbody>
                    {history.thirdPlaceCandidates.map((team) => (
                      <tr key={team.teamId} className="border-t border-stone-100">
                        <td className="py-1 pr-3 text-ink">{team.teamName}</td>
                        <td className="py-1 pr-3 tabular-nums">{team.played}</td>
                        <td className="py-1 pr-3 tabular-nums">{team.points}</td>
                        <td className="py-1 pr-3 tabular-nums">{team.goalDifference}</td>
                        <td className="py-1 tabular-nums">{team.goalsFor}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>
            )}
          </Card>

          <div className="grid grid-cols-1 gap-6 md:grid-cols-2">
            <Card title="Valla menos vencida" description="Menos goles recibidos por partido jugado.">
              {history.bestDefence ? (
                <div>
                  <p className="text-lg font-semibold text-ink">{history.bestDefence.teamName}</p>
                  <p className="text-sm text-stone-600">
                    {history.bestDefence.goalsConceded} {history.bestDefence.goalsConceded === 1 ? 'gol recibido' : 'goles recibidos'} en{' '}
                    {history.bestDefence.matchesPlayed} {history.bestDefence.matchesPlayed === 1 ? 'partido' : 'partidos'} (
                    {goalsPerMatch(history.bestDefence.goalsConceded, history.bestDefence.matchesPlayed)} por partido)
                  </p>
                </div>
              ) : (
                <p className="text-sm text-stone-500">No hubo partidos jugados.</p>
              )}
            </Card>

            <Card title={history.topScorers.length > 1 ? 'Goleadores' : 'Goleador'}>
              {history.topScorers.length === 0 ? (
                <p className="text-sm text-stone-500">No se registraron goles.</p>
              ) : (
                <ul className="flex flex-col divide-y divide-stone-100" aria-label="Goleadores">
                  {history.topScorers.map((scorer) => (
                    <li key={scorer.playerId} className="flex items-center justify-between gap-2 py-1.5 text-sm">
                      <span className="min-w-0">
                        <span className="block truncate font-semibold text-ink">{scorer.playerName}</span>
                        <span className="block text-xs text-stone-500">{scorer.teamName}</span>
                      </span>
                      <span className="shrink-0 tabular-nums text-stone-700">
                        {scorer.goals} {scorer.goals === 1 ? 'gol' : 'goles'}
                      </span>
                    </li>
                  ))}
                </ul>
              )}
            </Card>
          </div>

          <SanctionedTeamsCard tournamentId={tournamentId} history={history} canManage={canManage} onChanged={reload} />

          <PodiumPhotoViewer tournamentId={tournamentId} entry={viewing} onClose={() => setViewing(null)} />
          {canManage && (
            <PodiumPhotoEditor
              key={editing?.position ?? 0}
              tournamentId={tournamentId}
              entry={editing}
              onChanged={reload}
              onClose={() => setEditing(null)}
            />
          )}
        </div>
      )}
    </QueryState>
  )
}
