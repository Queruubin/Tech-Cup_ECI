package edu.escuelaing.techcup.competition.domain;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.identity.domain.AppUser;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * {@link Lineup#replacePlayers(List)} must behave as a delta: re-saving a lineup for the same
 * roster keeps the existing child rows (same composite key) instead of deleting and re-inserting
 * them, which is what made the second {@code PUT /matches/{id}/lineups} fail with a 409.
 */
class LineupTest {

    @Test
    void resavingKeepsExistingRowsAndOnlyTogglesTheStarterFlag() {
        Lineup lineup = Lineup.builder().id(5L).build();
        lineup.replacePlayers(List.of(player(1L, true), player(2L, true), player(3L, false)));
        List<LineupPlayer> firstRows = List.copyOf(lineup.getPlayers());

        lineup.replacePlayers(List.of(player(1L, true), player(2L, false), player(3L, true)));

        assertThat(lineup.getPlayers()).hasSize(3);
        assertThat(lineup.getPlayers()).containsExactlyElementsOf(firstRows);
        assertThat(starterOf(lineup, 1L)).isTrue();
        assertThat(starterOf(lineup, 2L)).isFalse();
        assertThat(starterOf(lineup, 3L)).isTrue();
        assertThat(lineup.getPlayers()).allSatisfy(row -> assertThat(row.getLineup()).isSameAs(lineup));
    }

    @Test
    void resavingRemovesPlayersNoLongerListedAndAddsNewOnes() {
        Lineup lineup = Lineup.builder().id(5L).build();
        lineup.replacePlayers(List.of(player(1L, true), player(2L, true)));
        LineupPlayer keptRow = lineup.getPlayers().get(0);

        lineup.replacePlayers(List.of(player(1L, false), player(3L, true)));

        assertThat(lineup.getPlayers()).extracting(LineupPlayer::playerId).containsExactly(1L, 3L);
        assertThat(lineup.getPlayers().get(0)).isSameAs(keptRow);
        assertThat(keptRow.isStarter()).isFalse();
        assertThat(lineup.getPlayers().get(1).getLineup()).isSameAs(lineup);
    }

    @Test
    void anEmptyListClearsTheLineup() {
        Lineup lineup = Lineup.builder().build();
        lineup.replacePlayers(List.of(player(1L, true)));

        lineup.replacePlayers(List.of());

        assertThat(lineup.getPlayers()).isEmpty();
    }

    private static boolean starterOf(Lineup lineup, Long playerId) {
        return lineup.getPlayers().stream()
                .filter(row -> row.playerId().equals(playerId))
                .findFirst()
                .orElseThrow()
                .isStarter();
    }

    private static LineupPlayer player(Long userId, boolean starter) {
        return LineupPlayer.of(AppUser.builder().id(userId).fullName("Player " + userId).build(), starter);
    }
}
