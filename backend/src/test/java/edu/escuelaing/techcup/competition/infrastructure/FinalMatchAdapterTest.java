package edu.escuelaing.techcup.competition.infrastructure;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import edu.escuelaing.techcup.competition.domain.MatchPhase;
import edu.escuelaing.techcup.competition.domain.MatchStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/** When the FINAL counts as decided for the "finish early" rule. */
@ExtendWith(MockitoExtension.class)
class FinalMatchAdapterTest {

    @Mock
    private MatchRepository matches;

    private FinalMatchAdapter adapter;

    @BeforeEach
    void setUp() {
        adapter = new FinalMatchAdapter(matches);
    }

    @Test
    void aPlayedFinalDecidesTheTournament() {
        when(matches.existsByTournamentIdAndPhaseAndStatus(1L, MatchPhase.FINAL, MatchStatus.PLAYED)).thenReturn(true);

        assertThat(adapter.isFinalMatchPlayed(1L)).isTrue();
    }

    @Test
    void aFinalCancelledWithAWalkoverWinnerDecidesTheTournamentLikeAPlayedOne() {
        when(matches.existsByTournamentIdAndPhaseAndStatus(1L, MatchPhase.FINAL, MatchStatus.PLAYED)).thenReturn(false);
        when(matches.existsByTournamentIdAndPhaseAndStatusAndWalkoverWinnerTeamIsNotNull(
                1L, MatchPhase.FINAL, MatchStatus.CANCELLED)).thenReturn(true);

        assertThat(adapter.isFinalMatchPlayed(1L)).isTrue();
    }

    @Test
    void aFinalStillToBePlayedDoesNotDecideTheTournament() {
        when(matches.existsByTournamentIdAndPhaseAndStatus(1L, MatchPhase.FINAL, MatchStatus.PLAYED)).thenReturn(false);
        when(matches.existsByTournamentIdAndPhaseAndStatusAndWalkoverWinnerTeamIsNotNull(
                1L, MatchPhase.FINAL, MatchStatus.CANCELLED)).thenReturn(false);

        assertThat(adapter.isFinalMatchPlayed(1L)).isFalse();
    }
}
