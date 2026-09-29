package edu.escuelaing.techcup.shared.config;

import java.time.Clock;
import java.time.ZoneId;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Exposes the application clock as a bean. Every rule that compares against "today" or "now"
 * (tournament start and finish dates, registration deadline, match kick-off) reads it through
 * this seam, which makes those rules unit-testable with a fixed clock.
 *
 * <p>The zone comes from {@code app.time-zone} rather than from the JVM default, so the date
 * rules behave the same on a developer machine and inside a UTC container.
 */
@Configuration
public class TimeConfig {

    @Bean
    public Clock clock(AppProperties properties) {
        return Clock.system(ZoneId.of(properties.timeZone()));
    }
}
