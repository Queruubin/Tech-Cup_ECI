package edu.escuelaing.techcup.competition.api.dto;

import java.util.List;

/**
 * Outcome of "Generar partidos" (every group at once): how many matches were created, per group,
 * the created matches, and a Spanish summary for the organizer. {@code created} is 0 when every
 * group already had all its pairings (the request is idempotent and says so in {@code message}).
 */
public record GroupMatchesGeneratedResponse(int created, List<GroupCount> groups, List<MatchResponse> matches,
                                            String message) {

    /** Matches created in one group ({@code created} is 0 for a complete group or one with fewer than 2 teams). */
    public record GroupCount(Long groupId, String name, int teams, int created) {
    }
}
