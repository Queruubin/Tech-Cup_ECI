package edu.escuelaing.techcup.competition.api.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * The first knockout round as the organizer confirmed it, in bracket order: 4 pairings make the
 * quarter-finals, 2 the semi-finals and 1 the final.
 */
public record CreateKnockoutRequest(
        @NotEmpty(message = "Indique los cruces de la eliminatoria.")
        @Valid List<@NotNull(message = "Los cruces no pueden estar vacíos.") Pairing> pairings) {

    public record Pairing(
            @NotNull(message = "El equipo local de cada cruce es obligatorio.") Long homeTeamId,
            @NotNull(message = "El equipo visitante de cada cruce es obligatorio.") Long awayTeamId) {
    }
}
