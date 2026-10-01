package edu.escuelaing.techcup.shared.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables {@code @Scheduled} housekeeping tasks (for example the purge of expired login-failure
 * counters in {@code LoginAttemptService}). They run on Spring Boot's auto-configured
 * single-thread scheduler.
 */
@Configuration
@EnableScheduling
public class SchedulingConfig {
}
