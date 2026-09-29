package edu.escuelaing.techcup.shared.exception;

/**
 * The request is well-formed but its content cannot be accepted, e.g. a wrong current password
 * (HTTP 400). Unlike {@link BusinessRuleException} it does not describe a conflict with existing
 * state. The message is written in Spanish because the frontend shows it verbatim.
 */
public class InvalidRequestException extends RuntimeException {

    public InvalidRequestException(String message) {
        super(message);
    }
}
