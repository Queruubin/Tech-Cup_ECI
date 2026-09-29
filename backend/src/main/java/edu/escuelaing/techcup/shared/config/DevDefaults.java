package edu.escuelaing.techcup.shared.config;

/**
 * The development-only secrets shipped in {@code application.yml}. They exist so that a fresh
 * checkout runs with no configuration at all; {@link ProductionGuard} refuses to start the
 * {@code prod} profile while any of them is still in use.
 */
public final class DevDefaults {

    public static final String JWT_SECRET = "techcup-dev-only-secret-change-me-please-0123456789abcdef";
    public static final String ADMIN_PASSWORD = "Admin123*";

    private DevDefaults() {
    }
}
