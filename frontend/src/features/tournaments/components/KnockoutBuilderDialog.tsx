import { useState } from 'react'
import { Button } from '@/components/atoms/Button'
import { Select } from '@/components/atoms/Select'
import { Alert } from '@/components/molecules/Alert'
import { FormField } from '@/components/molecules/FormField'
import { Modal } from '@/components/molecules/Modal'
import { useMutation } from '@/lib/useQuery'
import { KNOCKOUT_SIZES, type KnockoutSize, type KnockoutSuggestion, type MatchResponse } from '@/types/api'
import { tournamentsApi } from '../api'
import {
  KNOCKOUT_SIZE_LABELS,
  emptyPairings,
  nextRoundHint,
  pairingTeamOptions,
  pairingsFromSuggestion,
  toCreateKnockoutRequest,
  validatePairings,
  type PairingDraft,
  type TeamOption,
} from '../groupStage'

export interface KnockoutBuilderDialogProps {
  open: boolean
  tournamentId: number
  /** APPROVED teams of the tournament. */
  teams: TeamOption[]
  onClose: () => void
  onCreated: (matches: MatchResponse[]) => void
}

/**
 * "Armar eliminatoria": choose the size, load the suggestion (group winners against runners-up of
 * other groups, or the general table without groups), edit any pairing with two team selects (a
 * team cannot be used twice) and confirm. The pairings are created in the order shown.
 */
export function KnockoutBuilderDialog({ open, tournamentId, teams, onClose, onCreated }: KnockoutBuilderDialogProps) {
  const [size, setSize] = useState<KnockoutSize>(4)
  const [pairings, setPairings] = useState<PairingDraft[]>(() => emptyPairings(4))
  const [suggestion, setSuggestion] = useState<KnockoutSuggestion | null>(null)
  const [touched, setTouched] = useState(false)

  const load = useMutation((chosen: KnockoutSize) => tournamentsApi.knockoutSuggestion(tournamentId, chosen))
  const create = useMutation(() => tournamentsApi.createKnockout(tournamentId, toCreateKnockoutRequest(pairings)))
  const validation = validatePairings(pairings)

  const changeSize = (next: KnockoutSize) => {
    setSize(next)
    setPairings(emptyPairings(next))
    setSuggestion(null)
    setTouched(false)
    load.reset()
  }

  const loadSuggestion = () =>
    load
      .mutate(size)
      .then((loaded) => {
        setSuggestion(loaded)
        setPairings(pairingsFromSuggestion(loaded))
        setTouched(false)
      })
      .catch(() => undefined)

  const setSlot = (index: number, side: keyof PairingDraft, value: string) =>
    setPairings((previous) => previous.map((pairing, position) => (position === index ? { ...pairing, [side]: value } : pairing)))

  const confirm = () => {
    setTouched(true)
    if (!validation.valid) return
    create
      .mutate()
      .then(onCreated)
      .catch(() => undefined)
  }

  const labelOf = (index: number, side: 'home' | 'away', value: string) => {
    const seed = suggestion?.pairings[index]?.[side]
    return seed && String(seed.teamId) === value ? seed.label : null
  }
  const hint = nextRoundHint(pairings.length)

  return (
    <Modal
      open={open}
      onClose={onClose}
      size="lg"
      title="Armar eliminatoria"
      description="Elija el tamaño, cargue la sugerencia y ajuste los cruces si lo necesita. Los partidos se crean en el orden mostrado."
      footer={
        <>
          <Button variant="outline" onClick={onClose} disabled={create.loading}>
            Cancelar
          </Button>
          <Button onClick={confirm} loading={create.loading}>
            Crear eliminatoria
          </Button>
        </>
      }
    >
      <div className="flex flex-col gap-4">
        <div className="flex flex-col gap-2 sm:flex-row sm:items-end">
          <FormField label="Tamaño" className="sm:flex-1">
            <Select
              options={KNOCKOUT_SIZES.map((value) => ({ value: String(value), label: KNOCKOUT_SIZE_LABELS[value] }))}
              value={String(size)}
              onChange={(event) => changeSize(Number(event.target.value) as KnockoutSize)}
            />
          </FormField>
          <Button variant="outline" onClick={loadSuggestion} loading={load.loading}>
            Cargar sugerencia
          </Button>
        </div>
        {load.error && <Alert kind="error">{load.error}</Alert>}
        {suggestion && (
          <Alert kind="info">
            {suggestion.source === 'GROUPS'
              ? 'Sugerencia según las tablas de los grupos: los primeros contra los segundos de otros grupos.'
              : 'El torneo no tiene grupos: la sugerencia sigue la tabla general (1° contra último).'}
          </Alert>
        )}
        <ol className="flex flex-col gap-3">
          {pairings.map((pairing, index) => {
            const error = touched ? validation.errors[index] : null
            return (
              <li key={index} className="rounded-xl border border-stone-200 p-3">
                <p className="mb-2 text-xs font-semibold uppercase tracking-wide text-stone-500">Cruce {index + 1}</p>
                <div className="grid grid-cols-1 gap-2 sm:grid-cols-[1fr_auto_1fr] sm:items-center">
                  <div className="flex flex-col gap-1">
                    <Select
                      aria-label={`Cruce ${index + 1}: local`}
                      options={pairingTeamOptions(teams, pairings, index, 'homeTeamId')}
                      placeholder="Elija un equipo"
                      value={pairing.homeTeamId}
                      invalid={!!error}
                      onChange={(event) => setSlot(index, 'homeTeamId', event.target.value)}
                    />
                    {labelOf(index, 'home', pairing.homeTeamId) && (
                      <span className="text-xs text-stone-500">{labelOf(index, 'home', pairing.homeTeamId)}</span>
                    )}
                  </div>
                  <span className="text-center text-sm font-medium text-stone-500">vs</span>
                  <div className="flex flex-col gap-1">
                    <Select
                      aria-label={`Cruce ${index + 1}: visitante`}
                      options={pairingTeamOptions(teams, pairings, index, 'awayTeamId')}
                      placeholder="Elija un equipo"
                      value={pairing.awayTeamId}
                      invalid={!!error}
                      onChange={(event) => setSlot(index, 'awayTeamId', event.target.value)}
                    />
                    {labelOf(index, 'away', pairing.awayTeamId) && (
                      <span className="text-xs text-stone-500">{labelOf(index, 'away', pairing.awayTeamId)}</span>
                    )}
                  </div>
                </div>
                {error && (
                  <p role="alert" className="mt-2 text-xs font-medium text-brand-600">
                    {error}
                  </p>
                )}
              </li>
            )
          })}
        </ol>
        {hint && <p className="text-xs text-stone-500">{hint} Las rondas siguientes se crean solas cuando se juega el último partido de cada ronda.</p>}
        {create.error && <Alert kind="error">{create.error}</Alert>}
      </div>
    </Modal>
  )
}
