package edu.escuelaing.techcup.shared.exception;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import edu.escuelaing.techcup.shared.config.TraceIdFilter;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Framework exceptions raised while Spring MVC resolves a request (before or around the
 * controller) reach the client as their proper 4xx with the {@link ApiError} body and trace id,
 * instead of falling through to the generic 500.
 */
class GlobalExceptionHandlerMvcTest {

    private static final String TRACE_ID = "0123456789ab";

    private MockMvc mvc;

    @RestController
    static class ProbeController {

        @PostMapping(value = "/probe/json", consumes = MediaType.APPLICATION_JSON_VALUE,
                produces = MediaType.APPLICATION_JSON_VALUE)
        Map<String, Object> json(@RequestBody Map<String, Object> body) {
            return body;
        }

        @PostMapping(value = "/probe/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
        String upload(@RequestPart("file") MultipartFile file) {
            return file.getOriginalFilename();
        }

        @GetMapping("/probe/header")
        String header(@RequestHeader("X-Probe") String probe) {
            return probe;
        }

        @GetMapping("/probe/gone")
        String gone() {
            throw new ResponseStatusException(HttpStatus.GONE, "internal detail");
        }
    }

    @BeforeEach
    void setUp() {
        mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    /** Regression: {@code POST /api/auth/register} sent as text/plain answered 500. */
    @Test
    void aBodyInAnUnsupportedFormatIsAnUnsupportedMediaType() throws Exception {
        mvc.perform(post("/probe/json").requestAttr(TraceIdFilter.ATTRIBUTE, TRACE_ID)
                        .contentType(MediaType.TEXT_PLAIN).content("hola"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.UNSUPPORTED_MEDIA_TYPE))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    /** Regression: {@code POST /api/tournaments/{id}/registrations} sent as JSON answered 500. */
    @Test
    void jsonSentToAMultipartEndpointIsAnUnsupportedMediaType() throws Exception {
        mvc.perform(post("/probe/upload").requestAttr(TraceIdFilter.ATTRIBUTE, TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"teamId\":1}"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.UNSUPPORTED_MEDIA_TYPE))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void anUnacceptableAcceptHeaderIsANotAcceptableStillAnsweredInJson() throws Exception {
        mvc.perform(post("/probe/json").requestAttr(TraceIdFilter.ATTRIBUTE, TRACE_ID)
                        .contentType(MediaType.APPLICATION_JSON).content("{}")
                        .accept(MediaType.IMAGE_PNG))
                .andExpect(status().isNotAcceptable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.NOT_ACCEPTABLE))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void aMissingRequiredHeaderIsABadRequest() throws Exception {
        mvc.perform(get("/probe/header").requestAttr(TraceIdFilter.ATTRIBUTE, TRACE_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.MISSING_HEADER))
                .andExpect(jsonPath("$.details[0].field").value("X-Probe"))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void anUnknownPathStaysANotFound() throws Exception {
        mvc.perform(get("/probe/nothing-here").requestAttr(TraceIdFilter.ATTRIBUTE, TRACE_ID))
                .andExpect(status().isNotFound());
    }

    @Test
    void anyOtherFrameworkClientErrorKeepsItsStatusWithAGenericMessage() throws Exception {
        mvc.perform(get("/probe/gone").requestAttr(TraceIdFilter.ATTRIBUTE, TRACE_ID))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.message").value(GlobalExceptionHandler.INVALID_REQUEST))
                .andExpect(jsonPath("$.traceId").value(TRACE_ID));
    }

    @Test
    void anUnparsableMultipartBodyIsABadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/tournaments/1/registrations");
        request.setAttribute(TraceIdFilter.ATTRIBUTE, TRACE_ID);

        ResponseEntity<ApiError> response = new GlobalExceptionHandler().invalidMultipart(
                new MultipartException("Failed to parse multipart servlet request"), request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody().message()).isEqualTo(GlobalExceptionHandler.INVALID_MULTIPART);
        assertThat(response.getBody().traceId()).isEqualTo(TRACE_ID);
    }
}
