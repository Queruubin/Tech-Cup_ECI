package edu.escuelaing.techcup.players.api.dto;

import edu.escuelaing.techcup.players.domain.JoinRequestDirection;
import edu.escuelaing.techcup.players.domain.JoinRequestStatus;
import edu.escuelaing.techcup.players.domain.Position;
import java.time.Instant;

/** A join request or an invitation; {@code direction} tells them apart. */
public record JoinRequestResponse(
        Long id,
        Long teamId,
        String teamName,
        Long playerId,
        String playerName,
        Position position,
        Integer jerseyNumber,
        JoinRequestStatus status,
        JoinRequestDirection direction,
        String message,
        Instant createdAt) {
}
