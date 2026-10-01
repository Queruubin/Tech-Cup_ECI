package edu.escuelaing.techcup.competition.api.dto;

import java.time.Instant;

/**
 * Correction of a match by an organizer while the tournament is IN_PROGRESS. Every field is
 * optional ({@code null} leaves it unchanged). {@code scheduledAt} may lie in the past, to record
 * when the match was actually played. The teams can only be replaced while the match has no
 * result (reopen it first).
 */
public record UpdateMatchRequest(Instant scheduledAt, Long venueId, Long refereeId, Long homeTeamId,
                                 Long awayTeamId) {

    public boolean changesTeams() {
        return homeTeamId != null || awayTeamId != null;
    }
}
