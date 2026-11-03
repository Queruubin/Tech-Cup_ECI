package edu.escuelaing.techcup.competition.api.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Records a team as sanctioned in a finished tournament, with an optional note. */
public record SanctionTeamRequest(
        @NotNull(message = "Indique el equipo sancionado.") Long teamId,
        @Size(max = 500, message = "La nota admite como máximo 500 caracteres.") String note) {
}
