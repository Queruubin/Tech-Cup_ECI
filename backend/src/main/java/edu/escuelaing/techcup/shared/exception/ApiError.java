package edu.escuelaing.techcup.shared.exception;

import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;

/**
 * Error body returned by every failing request:
 * {@code { timestamp, status, error, message, path, traceId, details?: [{field, message}] }}.
 * {@code traceId} is the request's reference code (also in the {@code X-Trace-Id} header and in
 * every server log line of the request), so a user can report it and an administrator find it.
 */
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String message,
        String path,
        String traceId,
        List<FieldError> details) {

    public record FieldError(String field, String message) {
    }

    public static ApiError of(HttpStatus status, String message, String path, String traceId,
                              List<FieldError> details) {
        return new ApiError(Instant.now(), status.value(), status.getReasonPhrase(), message, path, traceId,
                details == null || details.isEmpty() ? null : details);
    }
}
