package edu.escuelaing.techcup.shared.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Application settings bound from the {@code app.*} namespace of {@code application.yml}.
 * Every value has an environment-variable override (see README).
 */
@Validated
@ConfigurationProperties(prefix = "app")
public record AppProperties(
        @Valid Jwt jwt,
        @NotEmpty List<String> corsOrigins,
        @NotBlank String timeZone,
        @Valid Storage storage,
        @Valid Bootstrap bootstrap,
        @Valid @NotNull Player player) {

    public record Jwt(
            @NotBlank @Size(min = 32, message = "JWT secret must be at least 32 bytes") String secret,
            @Positive long expirationMinutes) {
    }

    public record Storage(@Positive long maxFileSizeBytes) {
    }

    /** Credentials of the administrator created on first start (see {@code AdminBootstrap}). */
    public record Bootstrap(
            @NotBlank @Email String adminEmail,
            @NotBlank @Size(min = 8, max = 72) String adminPassword) {
    }

    /** Inclusive age range, in full years, of the users that may hold the PLAYER role. */
    public record Player(@PositiveOrZero int minAge, @Positive int maxAge) {

        @AssertTrue(message = "app.player.min-age must not be greater than app.player.max-age")
        public boolean isRangeValid() {
            return minAge <= maxAge;
        }
    }
}
