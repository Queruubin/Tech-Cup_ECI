package edu.escuelaing.techcup.shared.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/** One server-generated reference code per request, visible to the logs, the headers and the error bodies. */
class TraceIdFilterTest {

    private final TraceIdFilter filter = new TraceIdFilter();

    @Test
    void theCodeIsInTheLoggingContextWhileTheRequestRunsAndEchoedInTheResponse() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tournaments");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenByTheChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seenByTheChain.set(MDC.get(TraceIdFilter.MDC_KEY)));

        String traceId = response.getHeader(TraceIdFilter.HEADER);
        assertThat(traceId).matches("[0-9a-f]{12}");
        assertThat(seenByTheChain.get()).isEqualTo(traceId);
        assertThat(TraceIdFilter.current(request)).isEqualTo(traceId);
        assertThat(MDC.get(TraceIdFilter.MDC_KEY)).as("cleared after the request").isNull();
    }

    @Test
    void aClientSuppliedCodeIsIgnored() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/tournaments");
        request.addHeader(TraceIdFilter.HEADER, "attacker\ninjected");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(TraceIdFilter.HEADER)).matches("[0-9a-f]{12}");
    }

    @Test
    void eachRequestGetsItsOwnCode() throws Exception {
        MockHttpServletResponse first = new MockHttpServletResponse();
        MockHttpServletResponse second = new MockHttpServletResponse();

        filter.doFilter(new MockHttpServletRequest(), first, (req, res) -> { });
        filter.doFilter(new MockHttpServletRequest(), second, (req, res) -> { });

        assertThat(first.getHeader(TraceIdFilter.HEADER)).isNotEqualTo(second.getHeader(TraceIdFilter.HEADER));
    }

    @Test
    void outsideAFilteredRequestAFreshCodeIsStillProduced() {
        assertThat(TraceIdFilter.current(new MockHttpServletRequest())).matches("[0-9a-f]{12}");
    }
}
