package edu.escuelaing.techcup.shared.exception;

import java.util.Locale;
import org.springframework.dao.DataIntegrityViolationException;

/**
 * Reads which database constraint a {@link DataIntegrityViolationException} violated, so a use
 * case can turn a race lost against a unique index into its usual business message.
 */
public final class DataIntegrity {

    private DataIntegrity() {
    }

    /** The violated constraint's name, or {@code "unknown"}; never the driver message (it may echo values). */
    public static String constraintName(DataIntegrityViolationException ex) {
        Throwable cause = ex.getCause();
        while (cause != null) {
            if (cause instanceof org.hibernate.exception.ConstraintViolationException hibernate
                    && hibernate.getConstraintName() != null) {
                return hibernate.getConstraintName();
            }
            cause = cause.getCause();
        }
        return "unknown";
    }

    /** Whether {@code ex} is a violation of {@code constraint} (compared case-insensitively). */
    public static boolean violates(DataIntegrityViolationException ex, String constraint) {
        return constraintName(ex).toLowerCase(Locale.ROOT).contains(constraint.toLowerCase(Locale.ROOT));
    }
}
