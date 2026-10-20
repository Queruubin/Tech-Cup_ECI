package edu.escuelaing.techcup.identity.api.dto;

import edu.escuelaing.techcup.shared.validation.MaxUtf8Bytes;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** An administrator setting a new password for another account. */
public record ResetPasswordRequest(
        @NotBlank(message = "La contraseña nueva es obligatoria.")
        @Size(min = 8, max = 72, message = "La contraseña debe tener entre 8 y 72 caracteres.")
        @Pattern(regexp = PasswordRules.UPPERCASE_AND_DIGIT, message = PasswordRules.UPPERCASE_AND_DIGIT_MESSAGE)
        @MaxUtf8Bytes(value = PasswordRules.MAX_BYTES, message = PasswordRules.MAX_BYTES_MESSAGE)
        String newPassword) {
}
