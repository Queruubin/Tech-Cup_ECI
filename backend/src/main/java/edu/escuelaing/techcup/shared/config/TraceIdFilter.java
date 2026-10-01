package edu.escuelaing.techcup.shared.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HexFormat;
import java.util.concurrent.ThreadLocalRandom;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Gives every request a short reference code (12 hex characters) that ties what the user sees to
 * what the server logged. The code is
 * <ul>
 *   <li>put in the SLF4J {@link MDC} under {@value #MDC_KEY}, so every log line written while the
 *       request is handled carries it (see {@code logging.pattern.level});</li>
 *   <li>echoed in the {@value #HEADER} response header;</li>
 *   <li>stored as a request attribute, from which every {@code ApiError} body takes its
 *       {@code traceId} (see {@link #current(HttpServletRequest)}).</li>
 * </ul>
 * It runs first, before the Spring Security filter chain, so authentication and authorization
 * failures carry the code too. A client-supplied header is deliberately ignored: the code is only
 * useful if the server generated it.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    public static final String MDC_KEY = "traceId";
    public static final String HEADER = "X-Trace-Id";
    public static final String ATTRIBUTE = TraceIdFilter.class.getName() + ".traceId";
    private static final int BYTES = 6;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        String traceId = newTraceId();
        request.setAttribute(ATTRIBUTE, traceId);
        response.setHeader(HEADER, traceId);
        MDC.put(MDC_KEY, traceId);
        try {
            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(MDC_KEY);
        }
    }

    /**
     * The reference code of {@code request}; falls back to the logging context and, when the
     * request never went through this filter (for example in a unit test), to a fresh code.
     */
    public static String current(HttpServletRequest request) {
        Object attribute = request == null ? null : request.getAttribute(ATTRIBUTE);
        if (attribute instanceof String traceId) {
            return traceId;
        }
        String fromMdc = MDC.get(MDC_KEY);
        return fromMdc != null ? fromMdc : newTraceId();
    }

    static String newTraceId() {
        byte[] bytes = new byte[BYTES];
        ThreadLocalRandom.current().nextBytes(bytes);
        return HexFormat.of().formatHex(bytes);
    }
}
