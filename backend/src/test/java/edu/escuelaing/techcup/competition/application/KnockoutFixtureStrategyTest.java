package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.competition.application.FixtureStrategy.Fixture;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Adjacent pairing in bracket order: match 1 against match 2, 3 against 4 (the order of the first
 * round, chosen by {@code KnockoutSeeding}, is what keeps the top seeds apart).
 */
class KnockoutFixtureStrategyTest {

    private final KnockoutFixtureStrategy strategy = new KnockoutFixtureStrategy();

    @Test
    void eightTeamsArePairedWithTheirNeighbour() {
        List<Fixture> fixtures = strategy.generate(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L));

        assertThat(fixtures).containsExactly(
                new Fixture(1, 1L, 2L),
                new Fixture(1, 3L, 4L),
                new Fixture(1, 5L, 6L),
                new Fixture(1, 7L, 8L));
    }

    @Test
    void fourTeamsGiveTwoSemiFinalsOfNeighbours() {
        List<Fixture> fixtures = strategy.generate(List.of(10L, 20L, 30L, 40L));

        assertThat(fixtures).containsExactly(
                new Fixture(1, 10L, 20L),
                new Fixture(1, 30L, 40L));
    }

    @Test
    void twoTeamsSeedTheFinal() {
        List<Fixture> fixtures = strategy.generate(List.of(3L, 9L));

        assertThat(fixtures).containsExactly(new Fixture(1, 3L, 9L));
    }

    @Test
    void winnersOfMatchesOneAndTwoMeetAndSoDoThreeAndFour() {
        // Winners of QF1..QF4 in bracket order: W1 v W2 (home = winner of the first match), W3 v W4.
        List<Fixture> fixtures = strategy.generate(List.of(101L, 102L, 103L, 104L));

        assertThat(fixtures).containsExactly(
                new Fixture(1, 101L, 102L),
                new Fixture(1, 103L, 104L));
    }

    @Test
    void theLegacyFoldPairsFirstWithLast() {
        // Only for brackets drawn before 2026-10: W1 v W4, W2 v W3.
        assertThat(strategy.fold(List.of(101L, 102L, 103L, 104L))).containsExactly(
                new Fixture(1, 101L, 104L),
                new Fixture(1, 102L, 103L));
        assertThatThrownBy(() -> strategy.fold(List.of(1L, 2L, 3L)))
                .isInstanceOf(BusinessRuleException.class);
    }

    @Test
    void everyKnockoutPhaseIsASingleRound() {
        assertThat(strategy.generate(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L)))
                .allSatisfy(fixture -> assertThat(fixture.roundNumber()).isEqualTo(1));
    }

    @Test
    void anOddNumberOfTeamsCannotFormABracket() {
        assertThatThrownBy(() -> strategy.generate(List.of(1L, 2L, 3L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("número par de equipos");
    }

    @Test
    void asingleTeamCannotFormABracket() {
        assertThatThrownBy(() -> strategy.generate(List.of(1L)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("al menos 2 equipos");
    }
}
