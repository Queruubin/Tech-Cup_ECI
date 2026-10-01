package edu.escuelaing.techcup.competition.api.dto;

import edu.escuelaing.techcup.competition.domain.MatchPhase;

/** Outcome of {@code POST /tournaments/{id}/matches/undo-phase}: the phase removed and how many matches it had. */
public record PhaseUndoneResponse(MatchPhase phase, int deletedMatches) {
}
