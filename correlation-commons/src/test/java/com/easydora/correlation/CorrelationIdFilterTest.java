package com.easydora.correlation;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CorrelationIdFilter is the birthplace of a business operation's
 * CorrelationId: reused from the client if present, generated otherwise.
 * RequestId is always freshly generated, once per HTTP request. Both must
 * be visible via MDC only while the request is being handled -- never
 * leaking into whatever request a pooled thread handles next.
 */
class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @Test
    void generatesANewCorrelationIdWhenTheClientSendsNone() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> correlationIdDuringChain = new AtomicReference<>();
        AtomicReference<String> requestIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> {
            correlationIdDuringChain.set(MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY));
            requestIdDuringChain.set(MDC.get(CorrelationConstants.REQUEST_ID_MDC_KEY));
        });

        assertThat(correlationIdDuringChain.get()).isNotBlank();
        assertThat(requestIdDuringChain.get()).isNotBlank();
        assertThat(response.getHeader(CorrelationConstants.CORRELATION_ID_HEADER))
                .isEqualTo(correlationIdDuringChain.get());
        assertThat(response.getHeader(CorrelationConstants.REQUEST_ID_HEADER))
                .isEqualTo(requestIdDuringChain.get());
    }

    @Test
    void reusesTheClientSuppliedCorrelationIdInsteadOfGeneratingANewOne() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/example");
        request.addHeader(CorrelationConstants.CORRELATION_ID_HEADER, "client-supplied-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> correlationIdDuringChain = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) ->
                correlationIdDuringChain.set(MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY)));

        assertThat(correlationIdDuringChain.get()).isEqualTo("client-supplied-id");
        assertThat(response.getHeader(CorrelationConstants.CORRELATION_ID_HEADER)).isEqualTo("client-supplied-id");
    }

    @Test
    void generatesADifferentRequestIdOnEveryRequestEvenWithTheSameCorrelationId() throws Exception {
        MockHttpServletResponse firstResponse = new MockHttpServletResponse();
        MockHttpServletResponse secondResponse = new MockHttpServletResponse();

        MockHttpServletRequest firstRequest = new MockHttpServletRequest("GET", "/example");
        firstRequest.addHeader(CorrelationConstants.CORRELATION_ID_HEADER, "same-correlation-id");
        filter.doFilter(firstRequest, firstResponse, (req, res) -> { });

        MockHttpServletRequest secondRequest = new MockHttpServletRequest("GET", "/example");
        secondRequest.addHeader(CorrelationConstants.CORRELATION_ID_HEADER, "same-correlation-id");
        filter.doFilter(secondRequest, secondResponse, (req, res) -> { });

        assertThat(firstResponse.getHeader(CorrelationConstants.REQUEST_ID_HEADER))
                .isNotEqualTo(secondResponse.getHeader(CorrelationConstants.REQUEST_ID_HEADER));
    }

    @Test
    void clearsMdcAfterTheRequestSoNothingLeaksIntoTheNextOneOnAPooledThread() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/example");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(MDC.get(CorrelationConstants.CORRELATION_ID_MDC_KEY)).isNull();
        assertThat(MDC.get(CorrelationConstants.REQUEST_ID_MDC_KEY)).isNull();
    }
}
