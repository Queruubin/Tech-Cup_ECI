package edu.escuelaing.techcup.shared.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** The prod profile must never start with the development secrets, and warns about risky exposure. */
class ProductionGuardTest {

    private static final String STRONG_SECRET = "a-random-secret-that-is-definitely-longer-than-32-bytes";
    private static final String DEV_MONGO_URI = "mongodb://techcup:techcup@127.0.0.1:27018/techcup?authSource=admin";
    private static final String PUBLIC_ORIGIN = "https://techcup.example.org";

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
        MockEnvironment environment = prodEnvironment()
                .withProperty(ProductionGuard.DB_PASSWORD_PROPERTY, DevDefaults.DB_PASSWORD)
                .withProperty(ProductionGuard.MONGO_URI_PROPERTY, DEV_MONGO_URI);

        assertThat(guard(environment, DevDefaults.JWT_SECRET, DevDefaults.ADMIN_PASSWORD, PUBLIC_ORIGIN).problems())
                .hasSize(4);
    }

    @Test
    void prodRefusesTheDevelopmentDatabasePassword() {
        MockEnvironment environment = prodEnvironment()
                .withProperty(ProductionGuard.DB_PASSWORD_PROPERTY, DevDefaults.DB_PASSWORD);

        assertThatThrownBy(guard(environment, STRONG_SECRET, "Str0ngAdminPass", PUBLIC_ORIGIN)::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DB_PASSWORD is the development default");
    }

    @Test
    void prodRefusesTheDevelopmentMongoCredentials() {
        MockEnvironment environment = prodEnvironment()
                .withProperty(ProductionGuard.MONGO_URI_PROPERTY, DEV_MONGO_URI);

        assertThatThrownBy(guard(environment, STRONG_SECRET, "Str0ngAdminPass", PUBLIC_ORIGIN)::afterSingletonsInstantiated)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("MONGO_URI uses the development MongoDB credentials");
    }

    @Test
    void prodAcceptsOwnDatabaseCredentials() {
        MockEnvironment environment = prodEnvironment()
                .withProperty(ProductionGuard.DB_PASSWORD_PROPERTY, "9f2c4e1a7b")
                .withProperty(ProductionGuard.MONGO_URI_PROPERTY,
                        "mongodb://techcup:5d1e9a0c3b@127.0.0.1:27018/techcup?authSource=admin");

        assertThat(guard(environment, STRONG_SECRET, "Str0ngAdminPass", PUBLIC_ORIGIN).problems()).isEmpty();
    }

    @Test
    void aNonLoopbackAddressAndALocalhostOriginOnlyWarn() {
        MockEnvironment environment = prodEnvironment()
                .withProperty(ProductionGuard.SERVER_ADDRESS_PROPERTY, "0.0.0.0");
        ProductionGuard guard = guard(environment, STRONG_SECRET, "Str0ngAdminPass", "http://localhost:5173");

        assertThatCode(guard::afterSingletonsInstantiated).doesNotThrowAnyException();
        assertThat(guard.warnings()).hasSize(2)
                .anySatisfy(warning -> assertThat(warning).contains("'0.0.0.0', not a loopback address"))
                .anySatisfy(warning -> assertThat(warning).contains("CORS_ORIGINS contains a localhost origin"));
    }

    @Test
    void aLoopbackAddressAndAPublicOriginRaiseNoWarning() {
        MockEnvironment environment = prodEnvironment()
                .withProperty(ProductionGuard.SERVER_ADDRESS_PROPERTY, "127.0.0.1");

        assertThat(guard(environment, STRONG_SECRET, "Str0ngAdminPass", PUBLIC_ORIGIN).warnings()).isEmpty();
    }

    @Test
    void anUnsetAddressListensEverywhereAndIsWarnedAbout() {
        assertThat(guard(prodEnvironment(), STRONG_SECRET, "Str0ngAdminPass", PUBLIC_ORIGIN).warnings())
                .singleElement().asString().contains("not set");
    }

    @Test
    void onlyLoopbackLiteralsCountAsLoopback() {
        assertThat(ProductionGuard.isLoopback("127.0.0.1")).isTrue();
        assertThat(ProductionGuard.isLoopback("::1")).isTrue();
        assertThat(ProductionGuard.isLoopback("[::1]")).isTrue();
        assertThat(ProductionGuard.isLoopback("localhost")).isTrue();
        assertThat(ProductionGuard.isLoopback("0.0.0.0")).isFalse();
        assertThat(ProductionGuard.isLoopback("10.0.0.5")).isFalse();
        assertThat(ProductionGuard.isLoopback("techcup.example.org")).isFalse();
        assertThat(ProductionGuard.isLoopback(null)).isFalse();
    }

    private static MockEnvironment prodEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("prod");
        return environment;
    }

    private static ProductionGuard guard(String profile, String secret, String adminPassword) {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        return guard(environment, secret, adminPassword, "http://localhost:5173");
    }

    private static ProductionGuard guard(MockEnvironment environment, String secret, String adminPassword,
                                         String corsOrigin) {
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt(secret, 60),
                List.of(corsOrigin),
                "America/Bogota",
                new AppProperties.Storage(1024),
                new AppProperties.Bootstrap("admin@escuelaing.edu.co", adminPassword),
                new AppProperties.Player(5, 100));
        return new ProductionGuard(environment, properties);
    }
}
