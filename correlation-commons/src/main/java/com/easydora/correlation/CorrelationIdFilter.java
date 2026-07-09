package com.easydora.correlation;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.MDC;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Birthplace of a business operation's CorrelationId: reused from the
 * X-Correlation-Id request header if the client already sent one,
 * generated otherwise. RequestId is always freshly generated, once per
 * HTTP request, regardless of CorrelationId. Both are put in MDC for the
 * lifetime of the request so every log statement picks them up, and
 * echoed back as response headers. Stateless -- instantiate directly in
 * each service's SecurityConfig (`new CorrelationIdFilter()`) rather than
 * as a Spring-managed bean, since it has no collaborators to inject.
 */
public class CorrelationIdFilter extends OncePerRequestFilter {

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String incomingCorrelationId = request.getHeader(CorrelationConstants.CORRELATION_ID_HEADER);
        String correlationId = (incomingCorrelationId != null && !incomingCorrelationId.isBlank())
                ? incomingCorrelationId
                : CorrelationContext.newCorrelationId();
        String requestId = CorrelationContext.newMessageId();

        try {
            MDC.put(CorrelationConstants.CORRELATION_ID_MDC_KEY, correlationId);
            MDC.put(CorrelationConstants.REQUEST_ID_MDC_KEY, requestId);
            response.setHeader(CorrelationConstants.CORRELATION_ID_HEADER, correlationId);
            response.setHeader(CorrelationConstants.REQUEST_ID_HEADER, requestId);

            filterChain.doFilter(request, response);
        } finally {
            MDC.remove(CorrelationConstants.CORRELATION_ID_MDC_KEY);
            MDC.remove(CorrelationConstants.REQUEST_ID_MDC_KEY);
        }
    }
}
