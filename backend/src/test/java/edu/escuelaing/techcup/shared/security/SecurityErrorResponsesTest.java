package edu.escuelaing.techcup.shared.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import edu.escuelaing.techcup.shared.config.TraceIdFilter;
import edu.escuelaing.techcup.shared.exception.GlobalExceptionHandler;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.InsufficientAuthenticationException;

/** Failures inside the security filter chain answer with the same Spanish ApiError, trace id included. */
class SecurityErrorResponsesTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());
    private final SecurityErrorResponses responses = new SecurityErrorResponses(objectMapper);

    @Test
    void anUnauthenticatedRequestGetsASpanish401WithItsTraceId() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        responses.entryPoint().commence(request, response, new InsufficientAuthenticationException("Full authentication"));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(body.get("message").asText()).isEqualTo(GlobalExceptionHandler.AUTHENTICATION_FAILED);
        assertThat(body.get("traceId").asText()).isEqualTo("abcdef012345");
        assertThat(body.get("path").asText()).isEqualTo("/api/teams");
    }

    @Test
    void aForbiddenRequestGetsASpanish403WithItsTraceId() throws Exception {
        MockHttpServletRequest request = request();
        MockHttpServletResponse response = new MockHttpServletResponse();

        responses.accessDeniedHandler().handle(request, response, new AccessDeniedException("Access Denied"));

        JsonNode body = objectMapper.readTree(response.getContentAsByteArray());
        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(body.get("message").asText()).isEqualTo(GlobalExceptionHandler.ACCESS_DENIED);
        assertThat(body.get("traceId").asText()).isEqualTo("abcdef012345");
    }

    private static MockHttpServletRequest request() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/teams");
        request.setAttribute(TraceIdFilter.ATTRIBUTE, "abcdef012345");
        return request;
    }
}
