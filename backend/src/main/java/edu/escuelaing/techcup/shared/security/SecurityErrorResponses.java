package edu.escuelaing.techcup.shared.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.escuelaing.techcup.shared.config.TraceIdFilter;
import edu.escuelaing.techcup.shared.exception.ApiError;
import edu.escuelaing.techcup.shared.exception.GlobalExceptionHandler;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

/**
 * Writes the same {@link ApiError} JSON used by the global exception handler for failures that
 * happen inside the security filter chain (before any controller is reached): same Spanish
 * messages and the request's {@code traceId}.
 */
@Component
public class SecurityErrorResponses {

    private final ObjectMapper objectMapper;

    public SecurityErrorResponses(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public AuthenticationEntryPoint entryPoint() {
        return (request, response, ex) ->
                write(request, response, HttpStatus.UNAUTHORIZED, GlobalExceptionHandler.AUTHENTICATION_FAILED);
    }

    public AccessDeniedHandler accessDeniedHandler() {
        return (request, response, ex) ->
                write(request, response, HttpStatus.FORBIDDEN, GlobalExceptionHandler.ACCESS_DENIED);
    }

    private void write(HttpServletRequest request, HttpServletResponse response, HttpStatus status,
                       String message) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        objectMapper.writeValue(response.getOutputStream(),
                ApiError.of(status, message, request.getRequestURI(), TraceIdFilter.current(request), null));
    }
}
