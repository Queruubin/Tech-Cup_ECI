package edu.escuelaing.techcup.tournaments.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Partial update of a DRAFT tournament: every field is optional, nulls are ignored. */
public record UpdateTournamentRequest(
        @Size(max = 150, message = "El nombre del torneo no puede superar los 150 caracteres.")
        String name,
        LocalDate startDate,
        LocalDate endDate,
        LocalDate registrationDeadline,
        @Min(value = 2, message = "El torneo debe admitir al menos 2 equipos.")
        Integer maxTeams,
        @DecimalMin(value = "0.0", message = "El valor de la inscripción no puede ser negativo.")
        @DecimalMax(value = "9999999999.99", message = "El valor de la inscripción es demasiado alto.")
        BigDecimal fee) {
}
