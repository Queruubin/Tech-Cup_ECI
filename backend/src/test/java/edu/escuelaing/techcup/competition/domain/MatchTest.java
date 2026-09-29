package edu.escuelaing.techcup.competition.domain;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.tournaments.domain.Tournament;
import edu.escuelaing.techcup.tournaments.domain.TournamentStatus;
import org.junit.jupiter.api.Test;

/** When a result may still be recorded or corrected. */
class MatchTest {

    @Test
    void aScheduledMatchOfAnInProgressTournamentIsEditable() {
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchPhase.GROUP, MatchStatus.SCHEDULED)
                .isResultEditable(MatchPhase.SEMIFINAL)).isTrue();
    }

    @Test
    void aPlayedMatchStaysEditableWhileItIsInTheLatestPhase() {
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchPhase.GROUP, MatchStatus.PLAYED)
                .isResultEditable(MatchPhase.GROUP)).isTrue();
    }

    @Test
    void aPlayedMatchIsFrozenOnceALaterPhaseExists() {
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchPhase.GROUP, MatchStatus.PLAYED)
                .isResultEditable(MatchPhase.QUARTERFINAL)).isFalse();
    }

    @Test
    void aCancelledMatchIsNeverEditable() {
        assertThat(match(TournamentStatus.IN_PROGRESS, MatchPhase.GROUP, MatchStatus.CANCELLED)
                .isResultEditable(MatchPhase.GROUP)).isFalse();
    }

    @Test
    void nothingIsEditableOutsideAnInProgressTournament() {
        assertThat(match(TournamentStatus.FINISHED, MatchPhase.FINAL, MatchStatus.PLAYED)
                .isResultEditable(MatchPhase.FINAL)).isFalse();
        assertThat(match(TournamentStatus.ACTIVE, MatchPhase.GROUP, MatchStatus.SCHEDULED)
                .isResultEditable(MatchPhase.GROUP)).isFalse();
    }

    private static Match match(TournamentStatus tournamentStatus, MatchPhase phase, MatchStatus status) {
        return Match.builder()
                .id(3L)
                .tournament(Tournament.builder().id(1L).status(tournamentStatus).build())
                .phase(phase)
                .status(status)
                .build();
    }
}
