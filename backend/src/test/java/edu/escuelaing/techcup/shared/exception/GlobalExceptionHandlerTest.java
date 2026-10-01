package edu.escuelaing.techcup.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;

import edu.escuelaing.techcup.shared.config.TraceIdFilter;
import java.sql.SQLException;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Framework exceptions never leak their own message; domain exceptions keep theirs. */
class GlobalExceptionHandlerTest {

    private final GlobalExceptionHandler handler = new GlobalExceptionHandler();
    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/matches/abc");

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void typeMismatchAnswersWithAConstantMessageAndTheParameterName() {
        var ex = new MethodArgumentTypeMismatchException("abc", Long.class, "id", null,
                new NumberFormatException("For input string: \"abc\""));

        ResponseEntity<ApiError> response = handler.typeMismatch(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo(GlobalExceptionHandler.INVALID_PARAMETER);
        assertThat(response.getBody().message()).doesNotContain("abc", "Long");
        assertThat(response.getBody().details()).singleElement().extracting(ApiError.FieldError::field).isEqualTo("id");
    }

    @Test
    void missingParameterAnswersWithAConstantMessage() {
        var ex = new MissingServletRequestParameterException("reason", "CancelReason");

        ResponseEntity<ApiError> response = handler.missingParameter(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo(GlobalExceptionHandler.MISSING_PARAMETER);
        assertThat(response.getBody().details()).singleElement().extracting(ApiError.FieldError::field).isEqualTo("reason");
    }

    @Test
    void dataIntegrityHidesTheDriverMessageAndExposesOnlyTheConstraintName() {
        var ex = new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("duplicate key value",
                        new SQLException("Key (email)=(ana@escuelaing.edu.co) already exists."), "uk_users_email"));

        ResponseEntity<ApiError> response = handler.dataIntegrity(ex, request);

        assertThat(GlobalExceptionHandler.constraintName(ex)).isEqualTo("uk_users_email");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).doesNotContain("ana@escuelaing.edu.co");
    }

    @Test
    void aLostRaceOnAPendingRequestIndexGetsTheServiceMessage() {
        var ex = new DataIntegrityViolationException("could not execute statement",
                new org.hibernate.exception.ConstraintViolationException("duplicate key value",
                        new SQLException("Key (player_user_id)=(10) already exists."), "ux_join_requests_pending_request"));

        ResponseEntity<ApiError> response = handler.dataIntegrity(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).isEqualTo(Constraints.PENDING_JOIN_REQUEST_MESSAGE);
        assertThat(GlobalExceptionHandler.conflictMessage("ux_join_requests_pending_invitation"))
                .isEqualTo(Constraints.PENDING_INVITATION_MESSAGE);
        assertThat(GlobalExceptionHandler.conflictMessage("uk_users_email"))
                .isEqualTo("La solicitud entra en conflicto con la información ya registrada.");
    }

    @Test
    void optimisticLockFailuresBecomeARetryableConflict() {
        var ex = new ObjectOptimisticLockingFailureException("Match", 3L);

        ResponseEntity<ApiError> response = handler.concurrentModification(ex, request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().message()).isEqualTo(GlobalExceptionHandler.CONCURRENT_MODIFICATION);
    }

    @Test
    void loginThrottlingIsATooManyRequests() {
        ResponseEntity<ApiError> response = handler.tooManyAttempts(
                new LoginRateLimitException("Demasiados intentos fallidos."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getBody().message()).isEqualTo("Demasiados intentos fallidos.");
    }

    @Test
    void onlyTheLoginFailuresKeepTheirMessage() {
        assertThat(handler.unauthorized(new BadCredentialsException("El correo o la contraseña no son correctos."),
                request).getBody().message()).isEqualTo("El correo o la contraseña no son correctos.");
        assertThat(handler.unauthorized(new InsufficientAuthenticationException("Full authentication is required"),
                request).getBody().message()).isEqualTo(GlobalExceptionHandler.AUTHENTICATION_FAILED);
    }

    // --- access denied ----------------------------------------------------------------------

    @Test
    void accessDeniedWithoutAnyAuthenticationIsAnUnauthorized() {
        SecurityContextHolder.clearContext();

        ResponseEntity<ApiError> response = handler.accessDenied(new AccessDeniedException("Access Denied"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message()).isEqualTo(GlobalExceptionHandler.AUTHENTICATION_FAILED);
    }

    @Test
    void accessDeniedForAnAnonymousCallerIsAnUnauthorized() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken("key", "anonymousUser",
                List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        ResponseEntity<ApiError> response = handler.accessDenied(new AccessDeniedException("Access Denied"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(response.getBody().message()).isEqualTo(GlobalExceptionHandler.AUTHENTICATION_FAILED);
    }

    @Test
    void accessDeniedForAnAuthenticatedCallerStaysAForbidden() {
        SecurityContextHolder.getContext().setAuthentication(UsernamePasswordAuthenticationToken.authenticated(
                "player@escuelaing.edu.co", null, List.of(new SimpleGrantedAuthority("ROLE_PLAYER"))));

        ResponseEntity<ApiError> response = handler.accessDenied(new AccessDeniedException("Access Denied"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(response.getBody().message()).isEqualTo(GlobalExceptionHandler.ACCESS_DENIED);
        assertThat(response.getBody().message()).doesNotContain("Access Denied");
    }

    @Test
    void invalidRequestsKeepTheirDomainMessage() {
        ResponseEntity<ApiError> response = handler.invalidRequest(
                new InvalidRequestException("La contraseña actual no es correcta."), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo("La contraseña actual no es correcta.");
    }

    // --- trace id ---------------------------------------------------------------------------

    @Test
    void everyErrorCarriesTheTraceIdOfItsRequest() {
        request.setAttribute(TraceIdFilter.ATTRIBUTE, "0123456789ab");

        assertThat(handler.notFound(new NotFoundException("No existe."), request).getBody().traceId())
                .isEqualTo("0123456789ab");
        assertThat(handler.conflict(new BusinessRuleException("Regla."), request).getBody().traceId())
                .isEqualTo("0123456789ab");
        assertThat(handler.malformedBody(null, request).getBody().traceId()).isEqualTo("0123456789ab");
    }

    @Test
    @ExtendWith(OutputCaptureExtension.class)
    void anUnexpectedErrorIsLoggedWithItsStackTraceAndOnlyTheReferenceCodeReachesTheClient(CapturedOutput output) {
        request.setAttribute(TraceIdFilter.ATTRIBUTE, "fedcba987654");

        ResponseEntity<ApiError> response = handler.unexpected(
                new IllegalStateException("SELECT * FROM users failed"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(response.getBody().traceId()).isEqualTo("fedcba987654");
        assertThat(response.getBody().message()).isEqualTo("Ocurrió un error inesperado. Si el problema continúa, "
                + "comparta el código de referencia fedcba987654 con el administrador.");
        assertThat(response.getBody().message()).doesNotContain("SELECT");
        assertThat(output.getOut() + output.getErr())
                .contains("ERROR")
                .contains("traceId=fedcba987654")
                .contains("java.lang.IllegalStateException: SELECT * FROM users failed")
                .contains("at edu.escuelaing.techcup.shared.exception.GlobalExceptionHandlerTest");
    }
}
