package edu.escuelaing.techcup.tournaments.api.dto;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;

public record CreateTournamentRequest(
        @NotBlank(message = "El nombre del torneo es obligatorio.")
        @Size(max = 150, message = "El nombre del torneo no puede superar los 150 caracteres.")
        String name,
        @NotNull(message = "La fecha de inicio es obligatoria.")
        LocalDate startDate,
        @NotNull(message = "La fecha de cierre es obligatoria.")
        LocalDate endDate,
        @NotNull(message = "La fecha límite de inscripción es obligatoria.")
        LocalDate registrationDeadline,
        @NotNull(message = "El número máximo de equipos es obligatorio.")
        @Min(value = 2, message = "El torneo debe admitir al menos 2 equipos.")
        Integer maxTeams,
        @NotNull(message = "El valor de la inscripción es obligatorio.")
        @DecimalMin(value = "0.0", message = "El valor de la inscripción no puede ser negativo.")
        @DecimalMax(value = "9999999999.99", message = "El valor de la inscripción es demasiado alto.")
        BigDecimal fee) {
}
