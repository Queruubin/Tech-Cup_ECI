package edu.escuelaing.techcup.competition.api.dto;

import edu.escuelaing.techcup.competition.domain.MatchPhase;
import java.util.List;

/**
 * The suggested first knockout round, in bracket order (the winner of the first pairing meets the
 * winner of the last one in the next round, and so on). {@code source} is {@code GROUPS} when the
 * qualifiers come from the group tables and {@code GENERAL} when the tournament has no groups and
 * the general table was used. {@code label} explains each seed ("1° Grupo A", "3° general").
 */
public record KnockoutSuggestionResponse(MatchPhase phase, int size, String source, List<Pairing> pairings) {

    public record Pairing(Seed home, Seed away) {
    }

    public record Seed(Long teamId, String teamName, String label, Long groupId) {
    }
}
