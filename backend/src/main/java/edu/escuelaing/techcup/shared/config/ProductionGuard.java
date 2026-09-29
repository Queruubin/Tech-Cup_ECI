package edu.escuelaing.techcup.shared.config;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the {@code prod} profile is active with development secrets: a JWT secret
 * equal to the shipped default or shorter than {@value #MIN_SECRET_BYTES} bytes, or the default
 * bootstrap administrator password. Running with those values would let anyone forge tokens or
 * log in as the administrator.
 */
@Component
public class ProductionGuard implements SmartInitializingSingleton {

    static final String PROD_PROFILE = "prod";
    static final int MIN_SECRET_BYTES = 32;

    private final Environment environment;
    private final AppProperties properties;

    public ProductionGuard(Environment environment, AppProperties properties) {
        this.environment = environment;
        this.properties = properties;
    }

    @Override
    public void afterSingletonsInstantiated() {
        if (!environment.matchesProfiles(PROD_PROFILE)) {
            return;
        }
        List<String> problems = problems();
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Refusing to start with the 'prod' profile: "
                    + String.join(" ", problems)
                    + " Set JWT_SECRET (at least 32 random bytes, e.g. `openssl rand -base64 48`) and "
                    + "ADMIN_PASSWORD in the environment or in the .env file.");
        }
    }

    /** Every misconfiguration found, as a human-readable sentence; empty when the setup is safe. */
    List<String> problems() {
        List<String> problems = new ArrayList<>();
        String secret = properties.jwt().secret();
        if (DevDefaults.JWT_SECRET.equals(secret)) {
            problems.add("JWT_SECRET is the development default.");
        } else if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < MIN_SECRET_BYTES) {
            problems.add("JWT_SECRET is shorter than " + MIN_SECRET_BYTES + " bytes.");
        }
        if (DevDefaults.ADMIN_PASSWORD.equals(properties.bootstrap().adminPassword())) {
            problems.add("ADMIN_PASSWORD is the development default.");
        }
        return problems;
    }
}
