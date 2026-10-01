package edu.escuelaing.techcup.shared.config;

/**
 * The development-only secrets shipped in {@code application.yml} (and as defaults in
 * {@code docker-compose.yml}). They exist so that a fresh
 * checkout runs with no configuration at all; {@link ProductionGuard} refuses to start the
 * {@code prod} profile while any of them is still in use.
 */
public final class DevDefaults {

    public static final String JWT_SECRET = "techcup-dev-only-secret-change-me-please-0123456789abcdef";
    public static final String ADMIN_PASSWORD = "Admin123*";
    /** PostgreSQL password of the docker-compose development database ({@code DB_PASSWORD}). */
    public static final String DB_PASSWORD = "techcup";
    /** User and password of the docker-compose development MongoDB, as they appear in its URI. */
    public static final String MONGO_CREDENTIALS = "://techcup:techcup@";

    private DevDefaults() {
    }
}
