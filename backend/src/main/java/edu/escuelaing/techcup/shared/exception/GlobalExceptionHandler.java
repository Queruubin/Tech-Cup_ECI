package edu.escuelaing.techcup.shared.exception;

import edu.escuelaing.techcup.shared.config.TraceIdFilter;
import edu.escuelaing.techcup.shared.storage.InvalidFileException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.beans.TypeMismatchException;
import org.springframework.core.convert.ConversionFailedException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.ErrorResponse;
import org.springframework.web.HttpMediaTypeNotAcceptableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.ServletRequestBindingException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates exceptions into the {@link ApiError} contract. This is the "error handling" duty
 * of the specification's orchestrator, folded into the monolith.
 *
 * <p>Only the exceptions this code base raises on purpose (the {@code shared.exception} types,
 * {@link InvalidFileException} and the two authentication failures of the login use case) carry
 * a user-facing message. Every framework exception is mapped to a constant Spanish sentence so
 * that no internal detail (parameter types, SQL, class names) ever reaches a client.
 *
 * <p>Every body is JSON, whatever the request's {@code Accept} header asked for, so an error can
 * always be written (even the 406 that answers an unacceptable {@code Accept}).
 *
 * <p>Every body carries the request's {@code traceId} (see {@link TraceIdFilter}). An unexpected
 * exception is logged at ERROR with its stack trace and that code, and the client is asked to
 * share the code with an administrator instead of being shown any detail.
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
    public static final String AUTHENTICATION_FAILED = "No fue posible autenticar la solicitud.";
    public static final String ACCESS_DENIED = "No tiene permiso para realizar esta acción.";
    static final String CONCURRENT_MODIFICATION =
            "La información fue modificada por otra persona mientras usted la editaba. Intente de nuevo.";
    static final String UNSUPPORTED_MEDIA_TYPE = "El formato de la solicitud no es compatible con esta operación.";
    static final String NOT_ACCEPTABLE = "El formato de respuesta solicitado no está disponible para esta operación.";
    static final String INVALID_MULTIPART =
            "La solicitud debe enviarse como un formulario con archivos (multipart/form-data) válido.";
    static final String MISSING_HEADER = "Falta un encabezado obligatorio en la solicitud.";
    static final String INVALID_REQUEST = "La solicitud no es válida.";
    static final String NOT_FOUND = "No se encontró el recurso solicitado.";

    // --- domain exceptions --------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ApiError> notFound(NotFoundException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, ex.getMessage(), request, null);
    }

    @ExceptionHandler({ConflictException.class, BusinessRuleException.class})
    public ResponseEntity<ApiError> conflict(RuntimeException ex, HttpServletRequest request) {
        return respond(HttpStatus.CONFLICT, ex.getMessage(), request, null);
    }

    @ExceptionHandler(ForbiddenOperationException.class)
    public ResponseEntity<ApiError> forbidden(ForbiddenOperationException ex, HttpServletRequest request) {
        return respond(HttpStatus.FORBIDDEN, ex.getMessage(), request, null);
    }

    /**
     * {@code @PreAuthorize} failures. Under a {@code permitAll} path prefix (for example
     * {@code GET /api/tournaments/**}) an anonymous request reaches the controller and the method
     * guard throws this instead of the entry point answering first, so the caller must be told to
     * authenticate (401) rather than that its account lacks the role (403).
     */
    @ExceptionHandler(AccessDeniedException.class)
    public ResponseEntity<ApiError> accessDenied(AccessDeniedException ex, HttpServletRequest request) {
        if (!isAuthenticated(SecurityContextHolder.getContext().getAuthentication())) {
            return respond(HttpStatus.UNAUTHORIZED, AUTHENTICATION_FAILED, request, null);
        }
        return respond(HttpStatus.FORBIDDEN, ACCESS_DENIED, request, null);
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

    /** Any other value that could not be converted to the type a handler expects. */
    @ExceptionHandler({TypeMismatchException.class, ConversionFailedException.class})
    public ResponseEntity<ApiError> conversionFailed(RuntimeException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, INVALID_PARAMETER, request, null);
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

    /**
     * A multipart body that cannot be parsed, or a non-multipart request reaching a file part.
     * The more specific {@link MaxUploadSizeExceededException} keeps its own 413 above.
     */
    @ExceptionHandler(MultipartException.class)
    public ResponseEntity<ApiError> invalidMultipart(MultipartException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, INVALID_MULTIPART, request, null);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ApiError> missingHeader(MissingRequestHeaderException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, MISSING_HEADER, request,
                List.of(new ApiError.FieldError(ex.getHeaderName(), MISSING_HEADER)));
    }

    /** Remaining binding failures (missing cookie, unsatisfied parameter conditions...). */
    @ExceptionHandler(ServletRequestBindingException.class)
    public ResponseEntity<ApiError> requestBinding(ServletRequestBindingException ex, HttpServletRequest request) {
        return respond(HttpStatus.BAD_REQUEST, INVALID_REQUEST, request, null);
    }

    /** E.g. JSON sent to a multipart endpoint, or {@code text/plain} sent to a JSON one. */
    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<ApiError> unsupportedMediaType(HttpMediaTypeNotSupportedException ex,
                                                         HttpServletRequest request) {
        return respond(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UNSUPPORTED_MEDIA_TYPE, request, null);
    }

    @ExceptionHandler(HttpMediaTypeNotAcceptableException.class)
    public ResponseEntity<ApiError> notAcceptable(HttpMediaTypeNotAcceptableException ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_ACCEPTABLE, NOT_ACCEPTABLE, request, null);
    }

    // --- infrastructure / framework -------------------------------------------------------

    /**
     * A constraint the services normally check first, lost to a concurrent request (double
     * submit): known constraints get the same sentence the service would have used, any other one
     * a generic conflict.
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ApiError> dataIntegrity(DataIntegrityViolationException ex, HttpServletRequest request) {
        String constraint = constraintName(ex);
        log.warn("Data integrity violation on {} (constraint: {})", request.getRequestURI(), constraint);
        return respond(HttpStatus.CONFLICT, conflictMessage(constraint), request, null);
    }

    /** The user-facing sentence for a violated constraint. */
    static String conflictMessage(String constraint) {
        String name = constraint == null ? "" : constraint.toLowerCase(java.util.Locale.ROOT);
        if (name.contains(Constraints.PENDING_JOIN_REQUEST)) {
            return Constraints.PENDING_JOIN_REQUEST_MESSAGE;
        }
        if (name.contains(Constraints.PENDING_INVITATION)) {
            return Constraints.PENDING_INVITATION_MESSAGE;
        }
        return "La solicitud entra en conflicto con la información ya registrada.";
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<ApiError> concurrentModification(OptimisticLockingFailureException ex,
                                                           HttpServletRequest request) {
        log.info("Concurrent modification on {} {}", request.getMethod(), request.getRequestURI());
        return respond(HttpStatus.CONFLICT, CONCURRENT_MODIFICATION, request, null);
    }

    @ExceptionHandler({NoResourceFoundException.class, NoHandlerFoundException.class})
    public ResponseEntity<ApiError> noResource(Exception ex, HttpServletRequest request) {
        return respond(HttpStatus.NOT_FOUND, NOT_FOUND, request, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<ApiError> methodNotSupported(HttpRequestMethodNotSupportedException ex,
                                                       HttpServletRequest request) {
        return respond(HttpStatus.METHOD_NOT_ALLOWED, "La operación solicitada no está permitida sobre este recurso.", request, null);
    }

    /**
     * Last resort. A Spring MVC exception that declares a client error (any {@link ErrorResponse}
     * with a 4xx status not mapped above) keeps that status with a generic sentence instead of
     * becoming a 500; everything else is an unexpected failure.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> unexpected(Exception ex, HttpServletRequest request) {
        if (ex instanceof ErrorResponse errorResponse && errorResponse.getStatusCode().is4xxClientError()) {
            HttpStatus status = HttpStatus.resolve(errorResponse.getStatusCode().value());
            if (status != null) {
                log.info("Client error {} on {} {}: {}", status.value(), request.getMethod(), request.getRequestURI(),
                        ex.getClass().getSimpleName());
                return respond(status, INVALID_REQUEST, request, null);
            }
        }
        String traceId = TraceIdFilter.current(request);
        log.error("Unhandled exception [traceId={}] on {} {}", traceId, request.getMethod(), request.getRequestURI(), ex);
        return respond(HttpStatus.INTERNAL_SERVER_ERROR, unexpectedMessage(traceId), request, null);
    }

    /** The only thing a client learns about an unexpected failure: the code to report. */
    static String unexpectedMessage(String traceId) {
        return "Ocurrió un error inesperado. Si el problema continúa, comparta el código de referencia "
                + traceId + " con el administrador.";
    }

    // --- helpers ----------------------------------------------------------------------------

    /** The violated constraint's name only; the driver message may echo the offending values. */
    static String constraintName(DataIntegrityViolationException ex) {
        return DataIntegrity.constraintName(ex);
    }

    /** Anonymous requests carry either no authentication or the framework's anonymous token. */
    private static boolean isAuthenticated(Authentication authentication) {
        return authentication != null
                && authentication.isAuthenticated()
                && !(authentication instanceof AnonymousAuthenticationToken);
    }

    private static String parameterName(String name, int index) {
        return name != null ? name : "arg" + index;
    }

    private ResponseEntity<ApiError> respond(HttpStatus status, String message, HttpServletRequest request,
                                             List<ApiError.FieldError> details) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(ApiError.of(status, message, request.getRequestURI(), TraceIdFilter.current(request), details));
    }
}
