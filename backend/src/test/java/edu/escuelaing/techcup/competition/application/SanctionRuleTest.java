package edu.escuelaing.techcup.competition.application;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.competition.application.SanctionRule.PlayerFacts;
import edu.escuelaing.techcup.competition.application.SanctionRule.Sanction;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Who misses the next match: a sending off, or bookings whose running total crosses an even threshold. */
class SanctionRuleTest {

    @Test
    void aPlayerSentOffInThePreviousMatchIsSuspended() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(1L, "Ana", true, 0, 0)));

        assertThat(sanctions).singleElement().satisfies(sanction -> {
            assertThat(sanction.userId()).isEqualTo(1L);
            assertThat(sanction.reason()).isEqualTo("Expulsado en el partido anterior");
        });
    }

    @Test
    void asecondBookingSuspendsThePlayer() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(2L, "Bruno", false, 1, 2)));

        assertThat(sanctions).singleElement().satisfies(sanction ->
                assertThat(sanction.reason()).isEqualTo("Acumuló 2 tarjetas amarillas"));
    }

    @Test
    void afirstBookingDoesNotSuspendThePlayer() {
        assertThat(SanctionRule.apply(List.of(new PlayerFacts(2L, "Bruno", false, 1, 1)))).isEmpty();
    }

    @Test
    void anOddTotalReachedWithASingleBookingDoesNotSuspendThePlayer() {
        // 2 -> 3: the suspension for the second booking was already served.
        assertThat(SanctionRule.apply(List.of(new PlayerFacts(2L, "Bruno", false, 1, 3)))).isEmpty();
    }

    @Test
    void thefourthBookingSuspendsThePlayerAgain() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(2L, "Bruno", false, 1, 4)));

        assertThat(sanctions).singleElement().satisfies(sanction ->
                assertThat(sanction.reason()).isEqualTo("Acumuló 4 tarjetas amarillas"));
    }

    @Test
    void anEvenTotalReachedInAnEarlierMatchDoesNotSuspendAgain() {
        // The player already served that suspension: they were not booked in the previous match.
        assertThat(SanctionRule.apply(List.of(new PlayerFacts(2L, "Bruno", false, 0, 2)))).isEmpty();
    }

    @Test
    void aRedCardWinsOverAnAccumulation() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(3L, "Carla", true, 1, 2)));

        assertThat(sanctions).singleElement().satisfies(sanction ->
                assertThat(sanction.reason()).isEqualTo("Expulsado en el partido anterior"));
    }

    @Test
    void reportsEveryAffectedPlayerAndNobodyElse() {
        List<Sanction> sanctions = SanctionRule.apply(List.of(
                new PlayerFacts(1L, "Ana", true, 0, 0),
                new PlayerFacts(2L, "Bruno", false, 1, 2),
                new PlayerFacts(3L, "Carla", false, 1, 1),
                new PlayerFacts(4L, "Dario", false, 0, 0)));

        assertThat(sanctions).extracting(Sanction::userId).containsExactly(1L, 2L);
    }

    @Test
    void twoBookingsInOneMatchThatCrossAnEvenThresholdSuspendThePlayer() {
        // 1 -> 3: the total crossed 2 in the previous match without ever stopping on an even number.
        List<Sanction> sanctions = SanctionRule.apply(List.of(new PlayerFacts(2L, "Bruno", false, 2, 3)));

        assertThat(sanctions).singleElement().satisfies(sanction ->
                assertThat(sanction.reason()).isEqualTo("Acumuló 3 tarjetas amarillas"));
    }

    @ParameterizedTest
    @CsvSource({"0,1,false", "1,2,true", "2,3,false", "3,4,true", "1,3,true", "0,2,true", "2,4,true",
            "4,5,false", "5,6,true"})
    void accumulationTriggersWhenTheTotalCrossesAnEvenThreshold(int before, int after, boolean expected) {
        assertThat(SanctionRule.crossedAccumulation(before, after)).isEqualTo(expected);
    }
}
