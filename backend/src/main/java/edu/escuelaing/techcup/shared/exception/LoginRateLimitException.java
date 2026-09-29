package edu.escuelaing.techcup.shared.exception;

/**
 * Too many failed login attempts for the same account or client address (HTTP 429). The message
 * is written in Spanish because the frontend shows it verbatim to the end user.
 */
public class LoginRateLimitException extends RuntimeException {

    public LoginRateLimitException(String message) {
        super(message);
    }
}
