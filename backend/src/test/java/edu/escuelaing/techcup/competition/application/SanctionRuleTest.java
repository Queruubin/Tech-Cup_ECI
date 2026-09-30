package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.competition.application.SanctionRule.PlayerFacts;
import edu.escuelaing.techcup.competition.application.SanctionRule.Sanction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Who misses the next match: a sending off, or bookings adding up to an even number of at least two. */
class SanctionRuleTest {

    @Test
    void aPlayerSentOffInThePreviousMatchIsSuspended() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(1L, "Ana", true, false, 0)));

        assertThat(sanctions).singleElement().satisfies(sanction -> {
            assertThat(sanction.userId()).isEqualTo(1L);
            assertThat(sanction.reason()).isEqualTo("Expulsado en el partido anterior");
        });
    }

    @Test
    void asecondBookingSuspendsThePlayer() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(2L, "Bruno", false, true, 2)));

        assertThat(sanctions).singleElement().satisfies(sanction ->
                assertThat(sanction.reason()).isEqualTo("Acumuló 2 tarjetas amarillas"));
    }

    @Test
    void afirstBookingDoesNotSuspendThePlayer() {
        assertThat(SanctionRule.apply(List.of(new PlayerFacts(2L, "Bruno", false, true, 1)))).isEmpty();
    }

    @Test
    void anOddTotalDoesNotSuspendThePlayer() {
        assertThat(SanctionRule.apply(List.of(new PlayerFacts(2L, "Bruno", false, true, 3)))).isEmpty();
    }

    @Test
    void thefourthBookingSuspendsThePlayerAgain() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(2L, "Bruno", false, true, 4)));

        assertThat(sanctions).singleElement().satisfies(sanction ->
                assertThat(sanction.reason()).isEqualTo("Acumuló 4 tarjetas amarillas"));
    }

    @Test
    void anEvenTotalReachedInAnEarlierMatchDoesNotSuspendAgain() {
        // The player already served that suspension: they were not booked in the previous match.
        assertThat(SanctionRule.apply(List.of(new PlayerFacts(2L, "Bruno", false, false, 2)))).isEmpty();
    }

    @Test
    void aRedCardWinsOverAnAccumulation() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(3L, "Carla", true, true, 2)));

        assertThat(sanctions).singleElement().satisfies(sanction ->
                assertThat(sanction.reason()).isEqualTo("Expulsado en el partido anterior"));
    }

    @Test
    void reportsEveryAffectedPlayerAndNobodyElse() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(1L, "Ana", true, false, 0),
                new PlayerFacts(2L, "Bruno", false, true, 2),
                new PlayerFacts(3L, "Carla", false, true, 1),
                new PlayerFacts(4L, "Dario", false, false, 0)));

        assertThat(sanctions).extracting(Sanction::userId).containsExactly(1L, 2L);
    }

    @ParameterizedTest
    @CsvSource({"0,false", "1,false", "2,true", "3,false", "4,true", "5,false", "6,true"})
    void accumulationTriggersOnEveryEvenTotalFromTwoUpwards(int total, boolean expected) {
        assertThat(SanctionRule.reachedAccumulation(total)).isEqualTo(expected);
    }
}
