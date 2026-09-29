package edu.escuelaing.techcup.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The prod profile must never start with the development secrets. */
class ProductionGuardTest {

    private static final String STRONG_SECRET = "a-random-secret-that-is-definitely-longer-than-32-bytes";

    @Test
    void prodRefusesTheDevelopmentJwtSecret() {
        ProductionGuard guard = guard("prod", DevDefaults.JWT_SECRET, "Str0ngAdminPass");

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("JWT_SECRET is the development default");
    }

    @Test
    void prodRefusesAShortJwtSecret() {
        ProductionGuard guard = guard("prod", "too-short", "Str0ngAdminPass");

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shorter than 32 bytes");
    }

    @Test
    void prodRefusesTheDevelopmentAdminPassword() {
        ProductionGuard guard = guard("prod", STRONG_SECRET, DevDefaults.ADMIN_PASSWORD);

        assertThatThrownBy(guard::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ADMIN_PASSWORD is the development default");
    }

    @Test
    void prodStartsWithProperSecrets() {
        ProductionGuard guard = guard("prod", STRONG_SECRET, "Str0ngAdminPass");

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThat(guard.problems()).isEmpty();
    }

    @Test
    void developmentProfileToleratesTheDefaults() {
        ProductionGuard guard = guard("dev", DevDefaults.JWT_SECRET, DevDefaults.ADMIN_PASSWORD);

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
    }

    @Test
    void everyProblemIsReportedAtOnce() {
        assertThat(guard("prod", DevDefaults.JWT_SECRET, DevDefaults.ADMIN_PASSWORD).problems()).hasSize(2);
    }

    private static ProductionGuard guard(String profile, String secret, String adminPassword) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt(secret, 60),
                List.of("http://localhost:5173"),
                List.of("escuelaing.edu.co"),
                "America/Bogota",
                new AppProperties.Storage(1024),
                new AppProperties.Bootstrap("admin@escuelaing.edu.co", adminPassword));
        return new ProductionGuard(environment, properties);
    }
}
