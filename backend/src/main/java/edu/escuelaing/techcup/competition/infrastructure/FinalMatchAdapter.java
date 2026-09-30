package edu.escuelaing.techcup.competition.infrastructure;

import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import edu.escuelaing.techcup.tournaments.application.FinalMatchPort;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Answers the tournaments module's {@link FinalMatchPort} question without either module calling
 * the other's services, which keeps the dependency one-way (competition knows tournaments, never
 * the other way round).
 */
@Component
public class FinalMatchAdapter implements FinalMatchPort {

    private final MatchRepository matches;

    public FinalMatchAdapter(MatchRepository matches) {
        this.matches = matches;
    }

    @Override
    @Transactional(readOnly = true)
    public boolean isFinalMatchPlayed(Long tournamentId) {
        return matches.existsByTournamentIdAndPhaseAndStatus(tournamentId, MatchPhase.FINAL, MatchStatus.PLAYED);
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasScheduledMatches(Long tournamentId) {
        return matches.existsByTournamentIdAndStatus(tournamentId, MatchStatus.SCHEDULED);
    }
}
