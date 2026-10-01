import { describe, expect, it } from 'vitest'
import type { MatchPhase, MatchStatus } from '@/types/api'
import { undoPhaseConfirmDescription, undoPhaseState, undoPhaseSuccessMessage } from './undoPhase'

const m = (phase: MatchPhase, status: MatchStatus = 'SCHEDULED') => ({ phase, status })

describe('undoPhaseState', () => {
  it('is disabled when there are no matches', () => {
    expect(undoPhaseState([])).toMatchObject({ phase: null, count: 0, enabled: false })
  })

  it('targets the latest phase and ignores played matches of earlier phases', () => {
    const state = undoPhaseState([m('GROUP', 'PLAYED'), m('GROUP', 'PLAYED'), m('SEMIFINAL'), m('SEMIFINAL', 'CANCELLED')])
    expect(state).toEqual({ phase: 'SEMIFINAL', count: 2, enabled: true, reason: null })
  })

  it('is disabled when the latest phase has a played match', () => {
    const state = undoPhaseState([m('GROUP', 'PLAYED'), m('QUARTERFINAL', 'PLAYED'), m('QUARTERFINAL')])
    expect(state.phase).toBe('QUARTERFINAL')
    expect(state.enabled).toBe(false)
    expect(state.reason).toMatch(/partidos jugados/)
  })

  it('allows undoing an unplayed group fixture', () => {
    expect(undoPhaseState([m('GROUP'), m('GROUP', 'CANCELLED')])).toMatchObject({ phase: 'GROUP', count: 2, enabled: true })
  })
})

describe('undo phase copy', () => {
  it('describes what will be deleted and how to regenerate it', () => {
    expect(undoPhaseConfirmDescription('SEMIFINAL', 2)).toBe(
      'Se eliminarán los 2 partidos de las semifinales. Podrá volver a generar la fase con Avanzar fase.',
    )
    expect(undoPhaseConfirmDescription('GROUP', 6)).toMatch(/Generar fixture\.$/)
    expect(undoPhaseConfirmDescription('FINAL', 1)).toMatch(/^Se eliminará el partido de la final\./)
  })

  it('reports the server outcome', () => {
    expect(undoPhaseSuccessMessage('QUARTERFINAL', 4)).toBe('Fase de cuartos de final deshecha: 4 partidos eliminados.')
    expect(undoPhaseSuccessMessage('FINAL', 1)).toBe('Fase final deshecha: 1 partido eliminado.')
  })
})
