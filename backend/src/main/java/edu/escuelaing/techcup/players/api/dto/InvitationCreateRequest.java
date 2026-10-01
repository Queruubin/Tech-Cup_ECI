package edu.escuelaing.techcup.players.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** A captain invites a free player to the team. */
public record InvitationCreateRequest(
        @NotNull(message = "Indique el jugador que desea invitar.")
        Long playerId,
        @Size(max = 500, message = "El mensaje no puede superar los 500 caracteres.")
        String message) {
}
