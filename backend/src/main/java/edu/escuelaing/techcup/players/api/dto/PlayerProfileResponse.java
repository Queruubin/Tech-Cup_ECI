package edu.escuelaing.techcup.players.api.dto;

import edu.escuelaing.techcup.players.domain.Position;

public record PlayerProfileResponse(
        Long userId,
        String fullName,
        Position position,
        int jerseyNumber,
        String photoFileId,
        Long teamId,
        String teamName,
        /*
         * True while the player's team is locked by a tournament (APPROVED registration in an
         * ACTIVE, IN_PROGRESS or SUSPENDED one): the jersey number cannot change then.
         */
        boolean teamLocked) {
}
