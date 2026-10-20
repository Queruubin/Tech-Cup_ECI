package edu.escuelaing.techcup.tournaments.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Confirmation of a tournament hard delete: the administrator types the tournament name, which
 * must match it exactly (checked by the service, 400 otherwise).
 */
public record HardDeleteTournamentRequest(
        @NotBlank(message = "Escriba el nombre del torneo para confirmar la eliminación.")
        @Size(max = 150, message = "El nombre del torneo no puede superar los 150 caracteres.")
        String confirmName) {
}
