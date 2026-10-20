package edu.escuelaing.techcup.competition.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnockoutSeedingTest {

    @Test
    void twoGroupsAndFourQualifiersCrossWinnersWithTheOtherGroupsRunnerUp() {
        var suggestion = KnockoutSeeding.suggest(4, List.of(
                group(1L, "Grupo A", row(11L, "A1", 9), row(12L, "A2", 6), row(13L, "A3", 3)),
                group(2L, "Grupo B", row(21L, "B1", 7), row(22L, "B2", 4))), List.of());

        assertThat(suggestion.source()).isEqualTo("GROUPS");
        assertThat(suggestion.pairings()).hasSize(2);
        assertThat(pair(suggestion, 0)).containsExactly(11L, 22L);
        assertThat(pair(suggestion, 1)).containsExactly(21L, 12L);
        assertThat(suggestion.pairings().get(0).home().label()).isEqualTo("1° Grupo A");
        assertThat(suggestion.pairings().get(0).away().label()).isEqualTo("2° Grupo B");
    }

    @Test
    void fourGroupsAndEightQualifiersNeverPairTwoTeamsOfTheSameGroup() {
        var suggestion = KnockoutSeeding.suggest(8, List.of(
                group(1L, "Grupo A", row(11L, "A1", 9), row(12L, "A2", 6)),
                group(2L, "Grupo B", row(21L, "B1", 8), row(22L, "B2", 5)),
                group(3L, "Grupo C", row(31L, "C1", 7), row(32L, "C2", 4)),
                group(4L, "Grupo D", row(41L, "D1", 6), row(42L, "D2", 3))), List.of());

        assertThat(suggestion.pairings()).hasSize(4);
        // Winners at home, in seeding order; every runner-up from another group.
        assertThat(suggestion.pairings()).extracting(pairing -> pairing.home().teamId())
                .containsExactly(11L, 21L, 31L, 41L);
        assertThat(suggestion.pairings())
                .allSatisfy(pairing -> assertThat(pairing.home().groupId()).isNotEqualTo(pairing.away().groupId()));
        // The two teams of a group sit in different halves (pairings 1+4 and 2+3 meet in the semi-finals).
        for (int[] half : new int[][]{{0, 3}, {1, 2}}) {
            Set<Long> groups = new HashSet<>();
            for (int index : half) {
                groups.add(suggestion.pairings().get(index).home().groupId());
                groups.add(suggestion.pairings().get(index).away().groupId());
            }
            assertThat(groups).hasSize(4);
        }
    }

    @Test
    void whenALevelDoesNotFitTheBestTeamsOfThatLevelGoThrough() {
        var suggestion = KnockoutSeeding.suggest(4, List.of(
                group(1L, "Grupo A", row(11L, "A1", 9), row(12L, "A2", 3)),
                group(2L, "Grupo B", row(21L, "B1", 7), row(22L, "B2", 6)),
                group(3L, "Grupo C", row(31L, "C1", 5), row(32L, "C2", 1))), List.of());

        List<Long> qualified = new ArrayList<>();
        suggestion.pairings().forEach(pairing -> {
            qualified.add(pairing.home().teamId());
            qualified.add(pairing.away().teamId());
        });
        assertThat(qualified).containsExactlyInAnyOrder(11L, 21L, 31L, 22L);
        // B2 cannot face B1: the best winner (A1) gets it, B1 meets C1.
        assertThat(pair(suggestion, 0)).containsExactly(11L, 22L);
        assertThat(pair(suggestion, 1)).containsExactly(21L, 31L);
    }

    @Test
    void groupsWithDifferentSizesStillGiveEightQualifiers() {
        var suggestion = KnockoutSeeding.suggest(8, List.of(
                group(1L, "Grupo A", row(11L, "A1", 9), row(12L, "A2", 6), row(13L, "A3", 3), row(14L, "A4", 0)),
                group(2L, "Grupo B", row(21L, "B1", 6), row(22L, "B2", 3), row(23L, "B3", 0)),
                group(3L, "Grupo C", row(31L, "C1", 3), row(32L, "C2", 0))), List.of());

        assertThat(suggestion.pairings()).hasSize(4);
        assertThat(suggestion.pairings()).flatExtracting(pairing -> List.of(pairing.home().teamId(), pairing.away().teamId()))
                .doesNotHaveDuplicates()
                .hasSize(8)
                .doesNotContain(14L);
        assertThat(suggestion.pairings())
                .allSatisfy(pairing -> assertThat(pairing.home().groupId()).isNotEqualTo(pairing.away().groupId()));
    }

    @Test
    void withoutGroupsTheGeneralTableIsFoldedOneAgainstLast() {
        var suggestion = KnockoutSeeding.suggest(4, List.of(group(1L, "Grupo A")), List.of(
                generalRow(1, 1L, "One"), generalRow(2, 2L, "Two"), generalRow(3, 3L, "Three"),
                generalRow(4, 4L, "Four"), generalRow(5, 5L, "Five")));

        assertThat(suggestion.source()).isEqualTo("GENERAL");
        assertThat(pair(suggestion, 0)).containsExactly(1L, 4L);
        assertThat(pair(suggestion, 1)).containsExactly(2L, 3L);
        assertThat(suggestion.pairings().get(0).away().label()).isEqualTo("4° general");
    }

    @Test
    void aSingleGroupCannotAvoidItsOwnPairings() {
        var suggestion = KnockoutSeeding.suggest(4, List.of(
                group(1L, "Grupo A", row(11L, "A1", 9), row(12L, "A2", 6), row(13L, "A3", 3), row(14L, "A4", 0))),
                List.of());

        assertThat(pair(suggestion, 0)).containsExactly(11L, 14L);
        assertThat(pair(suggestion, 1)).containsExactly(12L, 13L);
    }

    @Test
    void notEnoughTeamsIsRefusedWithAClearMessage() {
        assertThatThrownBy(() -> KnockoutSeeding.suggest(8, List.of(
                group(1L, "Grupo A", row(11L, "A1", 9), row(12L, "A2", 6)),
                group(2L, "Grupo B", row(21L, "B1", 6))), List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Los grupos tienen 3 equipos en total; una eliminatoria de 8 equipos necesita al menos 8.");
        assertThatThrownBy(() -> KnockoutSeeding.suggest(4, List.of(), List.of(generalRow(1, 1L, "One"))))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("El torneo tiene 1 equipo aprobado;");
    }

    @Test
    void aSingleTeamInTheGroupsIsNamedInTheSingular() {
        assertThatThrownBy(() -> KnockoutSeeding.suggest(2, List.of(
                group(1L, "Grupo A", row(11L, "A1", 9))), List.of()))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Los grupos tienen 1 equipo en total; una eliminatoria de 2 equipos necesita al menos 2.");
    }

    @Test
    void theSizeDecidesThePhase() {
        assertThat(KnockoutSeeding.phaseFor(8)).isEqualTo(MatchPhase.QUARTERFINAL);
        assertThat(KnockoutSeeding.phaseFor(4)).isEqualTo(MatchPhase.SEMIFINAL);
        assertThat(KnockoutSeeding.phaseFor(2)).isEqualTo(MatchPhase.FINAL);
    }

    private static List<Long> pair(KnockoutSeeding.Suggestion suggestion, int index) {
        var pairing = suggestion.pairings().get(index);
        return List.of(pairing.home().teamId(), pairing.away().teamId());
    }

    private static KnockoutSeeding.GroupStanding group(Long id, String name, Standings.Row... rows) {
        List<Standings.Row> ordered = new ArrayList<>();
        for (int index = 0; index < rows.length; index++) {
            Standings.Row row = rows[index];
            ordered.add(new Standings.Row(index + 1, row.teamId(), row.teamName(), row.played(), row.won(),
                    row.drawn(), row.lost(), row.goalsFor(), row.goalsAgainst(), row.goalDifference(), row.points()));
        }
        return new KnockoutSeeding.GroupStanding(id, name, ordered);
    }

    private static Standings.Row row(Long teamId, String name, int points) {
        return new Standings.Row(0, teamId, name, 3, points / 3, points % 3, 0, points, 0, points, points);
    }

    private static Standings.Row generalRow(int position, Long teamId, String name) {
        return new Standings.Row(position, teamId, name, 0, 0, 0, 0, 0, 0, 0, 10 - position);
    }
}
