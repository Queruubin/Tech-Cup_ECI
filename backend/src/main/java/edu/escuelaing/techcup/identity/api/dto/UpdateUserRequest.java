package edu.escuelaing.techcup.identity.api.dto;

import edu.escuelaing.techcup.identity.domain.AcademicProgram;
import edu.escuelaing.techcup.identity.domain.SchoolRelation;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Editable basic information of a user; e-mail and password are immutable by design.
 *
 * <p>{@code schoolRelation} and {@code academicProgram} are optional: {@code null} keeps the stored
 * value. Referees are created without either, so requiring them would stop a referee from even
 * renaming themself.
 */
public record UpdateUserRequest(
        @NotBlank(message = "El nombre completo es obligatorio.")
        @Size(max = 150, message = "El nombre completo no puede superar los 150 caracteres.")
        String fullName,
        SchoolRelation schoolRelation,
        AcademicProgram academicProgram,
        @Min(value = 1, message = "El semestre debe estar entre 1 y 20.")
        @Max(value = 20, message = "El semestre debe estar entre 1 y 20.")
        Integer semester) {
}
