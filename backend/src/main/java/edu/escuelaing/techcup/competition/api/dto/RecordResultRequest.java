package edu.escuelaing.techcup.competition.api.dto;

import edu.escuelaing.techcup.competition.domain.EventType;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Final result of a match. {@code events} carries every goal and card; the number of GOAL events
 * of each team must match the score reported for that team. Penalties are required only to break
 * a draw in a knockout phase.
 */
public record RecordResultRequest(
        @NotNull(message = "Los goles del equipo local son obligatorios.")
        @Min(value = 0, message = "Los goles del equipo local no pueden ser negativos.")
        @Max(value = 99, message = "Los goles del equipo local no pueden superar 99.")
        Integer homeScore,
        @NotNull(message = "Los goles del equipo visitante son obligatorios.")
        @Min(value = 0, message = "Los goles del equipo visitante no pueden ser negativos.")
        @Max(value = 99, message = "Los goles del equipo visitante no pueden superar 99.")
        Integer awayScore,
        @Min(value = 0, message = "Los penales del equipo local no pueden ser negativos.")
        @Max(value = 99, message = "Los penales del equipo local no pueden superar 99.")
        Integer homePenalties,
        @Min(value = 0, message = "Los penales del equipo visitante no pueden ser negativos.")
        @Max(value = 99, message = "Los penales del equipo visitante no pueden superar 99.")
        Integer awayPenalties,
        @Size(max = 300, message = "Un partido no puede tener más de 300 eventos.")
        @Valid List<@NotNull(message = "Los eventos del partido no pueden estar vacíos.") Event> events) {

    public record Event(
            @NotNull(message = "El equipo del evento es obligatorio.") Long teamId,
            @NotNull(message = "El jugador del evento es obligatorio.") Long playerId,
            @NotNull(message = "El tipo de evento es obligatorio.") EventType type,
            @Min(value = 0, message = "El minuto no puede ser negativo.")
            @Max(value = 130, message = "El minuto no puede superar 130.")
            Integer minute) {
    }

    public List<Event> eventsOrEmpty() {
        return events == null ? List.of() : events;
    }
}
