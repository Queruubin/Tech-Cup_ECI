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
 *
 * <p>{@code phoneNumber} follows the same convention: {@code null} (absent) keeps the stored phone,
 * an empty or blank string removes it, anything else must be a valid phone ({@code PhoneNumbers})
 * and replaces it. It is contact data, so it stays editable while a tournament locks the team.
 */
public record UpdateUserRequest(
        @NotBlank(message = "El nombre completo es obligatorio.")
        @Size(max = 150, message = "El nombre completo no puede superar los 150 caracteres.")
        String fullName,
        SchoolRelation schoolRelation,
        AcademicProgram academicProgram,
        @Min(value = 1, message = "El semestre debe estar entre 1 y 20.")
        @Max(value = 20, message = "El semestre debe estar entre 1 y 20.")
        Integer semester,
        @PhoneNumber
        String phoneNumber) {
}
