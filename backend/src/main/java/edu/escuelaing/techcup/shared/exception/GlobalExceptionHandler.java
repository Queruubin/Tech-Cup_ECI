package edu.escuelaing.techcup.shared.exception;

import edu.escuelaing.techcup.shared.storage.InvalidFileException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into the {@link ApiError} contract. This is the "error handling" duty
 * of the specification's orchestrator, folded into the monolith.
 *
 * <p>Only the exceptions this code base raises on purpose (the {@code shared.exception} types,
 * {@link InvalidFileException} and the two authentication failures of the login use case) carry
 * a user-facing message. Every framework exception is mapped to a constant Spanish sentence so
 * that no internal detail (parameter types, SQL, class names) ever reaches a client.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Shown when one or more fields of the request are invalid; the offending fields go in {@code details}. */
    static final String VALIDATION_FAILED = "La información enviada no es válida. Revise los campos marcados.";
    static final String MALFORMED_BODY = "El cuerpo de la solicitud está mal formado.";
    static final String INVALID_PARAMETER = "Uno de los parámetros de la solicitud no tiene un valor válido.";
    static final String MISSING_PARAMETER = "Falta un parámetro obligatorio en la solicitud.";
    static final String MISSING_PART = "Falta un archivo o campo obligatorio en la solicitud.";
    static final String AUTHENTICATION_FAILED = "No fue posible autenticar la solicitud.";
    static final String CONCURRENT_MODIFICATION =
            "La información fue modificada por otra persona mientras usted la editaba. Intente de nuevo.";

    // --- domain exceptions --------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler({ConflictException.class, BusinessRuleException.class})
    public ResponseEntity<ApiError> conflict(RuntimeException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    @ExceptionHandler({ForbiddenOperationException.class, AccessDeniedException.class})
    public ResponseEntity<ApiError> forbidden(RuntimeException ex, HttpServletRequest request) {
        String message = ex instanceof ForbiddenOperationException
                ? ex.getMessage()
                : "No tiene permiso para realizar esta acción.";
        return respond(HttpStatus.FORBIDDEN, message, request, null);
    }

    @ExceptionHandler({InvalidRequestException.class, InvalidFileException.class})
    public ResponseEntity<ApiError> invalidRequest(RuntimeException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, ex.getMessage(), request, null);
    }

    @ExceptionHandler(LoginRateLimitException.class)
    public ResponseEntity<ApiError> tooManyAttempts(LoginRateLimitException ex, HttpServletRequest request) {
        return respond(HttpStatus.TOO_MANY_REQUESTS, ex.getMessage(), request, null);
    }

    /** Only the login use case's own failures carry a message; any other authentication error stays generic. */
    @ExceptionHandler(AuthenticationException.class)
    public ResponseEntity<ApiError> unauthorized(AuthenticationException ex, HttpServletRequest request) {
        String message = ex instanceof BadCredentialsException || ex instanceof DisabledException
                ? ex.getMessage()
                : AUTHENTICATION_FAILED;
        return respond(HttpStatus.UNAUTHORIZED, message, request, null);
    }

    // --- request validation ---------------------------------------------------------------

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ApiError> beanValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        List<ApiError.FieldError> details = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> new ApiError.FieldError(fe.getField(), fe.getDefaultMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, request, details);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ApiError> constraintViolation(ConstraintViolationException ex, HttpServletRequest request) {
        List<ApiError.FieldError> details = ex.getConstraintViolations().stream()
                .map(cv -> new ApiError.FieldError(cv.getPropertyPath().toString(), cv.getMessage()))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, request, details);
    }

    /** Constraint violations on {@code @RequestParam} / {@code @PathVariable} arguments (Spring 6.1+). */
    @ExceptionHandler(HandlerMethodValidationException.class)
    public ResponseEntity<ApiError> handlerMethodValidation(HandlerMethodValidationException ex,
                                                            HttpServletRequest request) {
        List<ApiError.FieldError> details = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ApiError.FieldError(parameterName(result.getMethodParameter().getParameterName(),
                                result.getMethodParameter().getParameterIndex()), error.getDefaultMessage())))
                .toList();
        return respond(HttpStatus.BAD_REQUEST, VALIDATION_FAILED, request, details);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ApiError> malformedBody(HttpMessageNotReadableException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, MALFORMED_BODY, request, null);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ApiError> typeMismatch(MethodArgumentTypeMismatchException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, INVALID_PARAMETER, request,
                List.of(new ApiError.FieldError(ex.getName(), INVALID_PARAMETER)));
    }

    @ExceptionHandler(MissingServletRequestParameterException.class)
    public ResponseEntity<ApiError> missingParameter(MissingServletRequestParameterException ex,
                                                     HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, MISSING_PARAMETER, request,
                List.of(new ApiError.FieldError(ex.getParameterName(), MISSING_PARAMETER)));
    }

    @ExceptionHandler(MissingServletRequestPartException.class)
    public ResponseEntity<ApiError> missingPart(MissingServletRequestPartException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, MISSING_PART, request,
                List.of(new ApiError.FieldError(ex.getRequestPartName(), MISSING_PART)));
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ResponseEntity<ApiError> uploadTooLarge(MaxUploadSizeExceededException ex, HttpServletRequest request) {
        return respond(HttpStatus.PAYLOAD_TOO_LARGE, "El archivo supera el tamaño máximo permitido.", request, null);
    }

    // --- infrastructure / framework -------------------------------------------------------

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        log.warn("Data integrity violation on {} (constraint: {})", request.getRequestURI(), constraintName(ex));
        return respond(HttpStatus.CONFLICT, "La solicitud entra en conflicto con la información ya registrada.", request, null);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> concurrentModification(OptimisticLockingFailureException ex,
                                                           HttpServletRequest request) {
        log.info("Concurrent modification on {} {}", request.getMethod(), request.getRequestURI());
        return respond(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION, request, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ApiError> noResource(NoResourceFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, "No se encontró el recurso solicitado.", request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "La operación solicitada no está permitida sobre este recurso.", request, null);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        log.error("Unhandled exception on {} {}", request.getMethod(), request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, "Ocurrió un error inesperado. Intente de nuevo más tarde.", request, null);
    }

    // --- helpers ----------------------------------------------------------------------------

    /** The violated constraint's name only; the driver message may echo the offending values. */
    static String constraintName(DataIntegrityViolationException ex) {
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

    private static String parameterName(String name, int index) {
        return name != null ? name : "arg" + index;
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String message, HttpServletRequest request,
                                             List<ApiError.FieldError> details) {
        return ResponseEntity.status(status).body(ApiError.of(status, message, request.getRequestURI(), details));
    }
}
