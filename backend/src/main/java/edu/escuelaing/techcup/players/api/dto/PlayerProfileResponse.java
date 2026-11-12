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
         * True while the player's team is locked by a tournament (APPROVED registration in one
         * that has started: IN_PROGRESS, or SUSPENDED from IN_PROGRESS): the jersey number cannot
         * change then.
         */
        boolean teamLocked) {
}
