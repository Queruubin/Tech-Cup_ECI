package edu.escuelaing.techcup.competition.api.dto;

import jakarta.validation.constraints.NotNull;
import java.time.Instant;

/**
 * A group match created by hand. Both teams must belong to the group; the venue (one of the
 * tournament) and the referee (an ACTIVE referee) are optional and can be set later.
 */
public record CreateMatchRequest(
        @NotNull(message = "El grupo es obligatorio.") Long groupId,
        @NotNull(message = "El equipo local es obligatorio.") Long homeTeamId,
        @NotNull(message = "El equipo visitante es obligatorio.") Long awayTeamId,
        @NotNull(message = "La fecha y hora del partido son obligatorias.") Instant scheduledAt,
        Long venueId,
        Long refereeId) {
}
