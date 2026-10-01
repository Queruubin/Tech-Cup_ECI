package edu.escuelaing.techcup.shared.config;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when the {@code prod} profile is active with development secrets: a JWT secret
 * equal to the shipped default or shorter than {@value #MIN_SECRET_BYTES} bytes, the default
 * bootstrap administrator password, the default PostgreSQL password or the default MongoDB
 * credentials. Running with those values would let anyone forge tokens, log in as the
 * administrator or read the databases.
 *
 * <p>It also logs a WARNING (without failing) for settings that are legitimate in some
 * deployments but usually a mistake: a {@code server.address} that is not a loopback address
 * (the backend is then reachable without going through the reverse proxy; expected inside a
 * container, whose port must then be published on loopback only) and {@code localhost} among the
 * CORS origins.
 */
@Component
public class ProductionGuard implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(ProductionGuard.class);

    static final String PROD_PROFILE = "prod";
    static final int MIN_SECRET_BYTES = 32;
    static final String DB_PASSWORD_PROPERTY = "spring.datasource.password";
    static final String MONGO_URI_PROPERTY = "spring.data.mongodb.uri";
    static final String SERVER_ADDRESS_PROPERTY = "server.address";

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
                    + " Set JWT_SECRET (at least 32 random bytes, e.g. `openssl rand -base64 48`), "
                    + "ADMIN_PASSWORD, DB_PASSWORD and the MongoDB credentials (MONGO_URI) in the environment "
                    + "or in the .env file.");
        }
        warnings().forEach(warning -> log.warn("Production configuration: {}", warning));
    }

    /** Every misconfiguration that blocks startup, as a human-readable sentence; empty when safe. */
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
        if (DevDefaults.DB_PASSWORD.equals(environment.getProperty(DB_PASSWORD_PROPERTY))) {
            problems.add("DB_PASSWORD is the development default.");
        }
        String mongoUri = environment.getProperty(MONGO_URI_PROPERTY);
        if (mongoUri != null && mongoUri.contains(DevDefaults.MONGO_CREDENTIALS)) {
            problems.add("MONGO_URI uses the development MongoDB credentials.");
        }
        return problems;
    }

    /** Settings worth a warning in production but not worth refusing to start. */
    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        String address = environment.getProperty(SERVER_ADDRESS_PROPERTY);
        if (!isLoopback(address)) {
            warnings.add("server.address (SERVER_ADDRESS) is " + (address == null || address.isBlank()
                    ? "not set, so the backend listens on every network interface"
                    : "'" + address + "', not a loopback address")
                    + "; make sure only the reverse proxy can reach the backend port.");
        }
        List<String> localOrigins = properties.corsOrigins().stream()
                .filter(origin -> origin != null && origin.toLowerCase(Locale.ROOT).contains("localhost"))
                .toList();
        if (!localOrigins.isEmpty()) {
            warnings.add("CORS_ORIGINS contains a localhost origin " + localOrigins
                    + "; set it to the public URL of the site.");
        }
        return warnings;
    }

    /**
     * Whether {@code address} is a loopback address. Only "localhost" and IP literals are accepted,
     * so this check never performs a DNS lookup.
     */
    static boolean isLoopback(String address) {
        if (address == null || address.isBlank()) {
            return false;
        }
        String value = address.trim();
        if ("localhost".equalsIgnoreCase(value)) {
            return true;
        }
        if (value.startsWith("[") && value.endsWith("]")) {
            value = value.substring(1, value.length() - 1);
        }
        boolean ipLiteral = value.contains(":") || value.matches("[0-9.]+");
        if (!ipLiteral) {
            return false;
        }
        try {
            return InetAddress.getByName(value).isLoopbackAddress();
        } catch (UnknownHostException ex) {
            return false;
        }
    }
}
