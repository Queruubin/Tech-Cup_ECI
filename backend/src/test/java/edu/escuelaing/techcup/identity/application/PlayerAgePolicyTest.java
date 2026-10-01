package edu.escuelaing.techcup.identity.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import edu.escuelaing.techcup.shared.config.AppProperties;
import edu.escuelaing.techcup.shared.exception.BusinessRuleException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class PlayerAgePolicyTest {

    /** 2026-03-10 in Bogota (the instant is 2026-03-11 01:00 UTC, so the zone matters). */
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-03-11T01:00:00Z"), ZoneId.of("America/Bogota"));

    private final PlayerAgePolicy policy = new PlayerAgePolicy(5, 100, CLOCK);

    @Test
    void theBoundsAreInclusiveInFullYears() {
        assertThat(policy.isAllowed(LocalDate.of(2021, 3, 10))).as("5th birthday today").isTrue();
        assertThat(policy.isAllowed(LocalDate.of(2021, 3, 11))).as("5th birthday tomorrow").isFalse();
        assertThat(policy.isAllowed(LocalDate.of(1925, 3, 11))).as("101st birthday tomorrow").isTrue();
        assertThat(policy.isAllowed(LocalDate.of(1925, 3, 10))).as("101st birthday today").isFalse();
    }

    @Test
    void todayComesFromTheConfiguredZone() {
        assertThat(policy.ageOn(LocalDate.of(2016, 3, 11))).isEqualTo(9);
        assertThat(policy.ageOn(LocalDate.of(2016, 3, 10))).isEqualTo(10);
    }

    @Test
    void anUnknownBirthDateIsNotAllowed() {
        assertThat(policy.isAllowed(null)).isFalse();
    }

    @Test
    void theRefusalNamesTheConfiguredBounds() {
        assertThatThrownBy(() -> policy.validate(LocalDate.of(1920, 1, 1)))
                .isInstanceOf(BusinessRuleException.class)
                .hasMessage("Para ser jugador la edad debe estar entre 5 y 100 años.");
        assertThatCode(() -> policy.validate(LocalDate.of(2016, 1, 1))).doesNotThrowAnyException();
    }

    @Test
    void readsTheBoundsFromTheProperties() {
        AppProperties properties = new AppProperties(
                new AppProperties.Jwt("x".repeat(40), 60),
                List.of("http://localhost:5173"),
                "America/Bogota",
                new AppProperties.Storage(1024),
                new AppProperties.Bootstrap("admin@escuelaing.edu.co", "Str0ngAdminPass"),
                new AppProperties.Player(6, 12));

        PlayerAgePolicy configured = new PlayerAgePolicy(properties, CLOCK);

        assertThat(configured.message()).isEqualTo("Para ser jugador la edad debe estar entre 6 y 12 años.");
        assertThat(configured.isAllowed(LocalDate.of(2021, 1, 1))).isFalse();
    }
}
