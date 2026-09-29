package edu.escuelaing.techcup.identity.api.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** An administrator setting a new password for another account. */
public record ResetPasswordRequest(
        @NotBlank(message = "La contraseña nueva es obligatoria.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        @Pattern(regexp = PasswordRules.LETTER_AND_DIGIT, message = PasswordRules.LETTER_AND_DIGIT_MESSAGE)
        String newPassword) {
}
