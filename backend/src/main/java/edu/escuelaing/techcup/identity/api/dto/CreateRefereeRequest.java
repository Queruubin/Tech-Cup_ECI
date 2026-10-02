package edu.escuelaing.techcup.identity.api.dto;

import edu.escuelaing.techcup.identity.domain.DocumentType;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Past;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;

public record CreateRefereeRequest(
        @NotBlank(message = "El nombre completo es obligatorio.")
        @Size(max = 150, message = "El nombre completo no puede superar los 150 caracteres.")
        String fullName,
        @NotBlank(message = "El correo es obligatorio.")
        @Email(message = "El correo no tiene un formato válido.")
        @Size(max = 150, message = "El correo no puede superar los 150 caracteres.")
        String email,
        @NotBlank(message = "La contraseña es obligatoria.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        @Pattern(regexp = PasswordRules.UPPERCASE_AND_DIGIT, message = PasswordRules.UPPERCASE_AND_DIGIT_MESSAGE)
        String password,
        @NotNull(message = "La fecha de nacimiento es obligatoria.")
        @Past(message = "La fecha de nacimiento debe ser anterior a hoy.")
        LocalDate birthDate,
        @NotNull(message = "El tipo de documento es obligatorio.")
        DocumentType documentType,
        @NotBlank(message = "El número de documento es obligatorio.")
        @Size(max = 30, message = "El número de documento no puede superar los 30 caracteres.")
        String documentNumber) {
}
