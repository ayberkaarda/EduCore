package com.educore.common.logging;

import com.educore.common.web.CorrelationIds;
import com.educore.security.RequestIdFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** {@link RequestIdFilter}: which incoming ids are kept, MDC lifecycle and the response header. */
class RequestIdFilterTest {

    private final RequestIdFilter filter = new RequestIdFilter();

    @AfterEach
    void clearMdc() {
        MDC.clear();
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "req-42", "client.trace_ID-0001", "3f2b8c1e-9d4a-4e7b-8f6a-1c2d3e4f5a6b"})
    void safeIncomingIdIsKeptInMdcAndResponse(String incoming) throws Exception {
        Result result = run(incoming);

        assertThat(result.idInMdc()).isEqualTo(incoming);
        assertThat(result.response().getHeader(RequestIdFilter.HEADER)).isEqualTo(incoming);
    }

    @Test
    void idOfExactly64CharactersIsKept() throws Exception {
        String incoming = "x".repeat(64);

        assertThat(run(incoming).idInMdc()).isEqualTo(incoming);
    }

    @ParameterizedTest
    @ValueSource(strings = {"abc\r\nX-Injected: 1", "abc\nINFO forged line", "abc\r", "with space", "a/b", "<script>",
            "id;drop", "ünicode", "%0d%0a", ""})
    void unsafeIncomingIdIsReplacedByAUuid(String incoming) throws Exception {
        Result result = run(incoming);

        assertGenerated(result);
        assertThat(result.idInMdc()).isNotEqualTo(incoming);
    }

    @Test
    void oversizedIncomingIdIsReplaced() throws Exception {
        assertGenerated(run("x".repeat(65)));
        assertGenerated(run("y".repeat(10_000)));
    }

    @Test
    void missingIdIsGenerated() throws Exception {
        assertGenerated(run(null));
    }

    @Test
    void mdcIsClearedAfterTheRequestEvenWhenTheChainFails() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/x");
        FilterChain failing = (req, res) -> {
            throw new IllegalStateException("downstream failure");
        };

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), failing))
                .isInstanceOf(IllegalStateException.class);
        assertThat(MDC.get(MdcKeys.REQUEST_ID)).isNull();
    }

    @Test
    void errorDispatchReusesTheIdAlreadySentToTheClient() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.addHeader(RequestIdFilter.HEADER, "client-sent-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.setHeader(RequestIdFilter.HEADER, "first-dispatch-id");
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(MdcKeys.REQUEST_ID)));

        assertThat(seen.get()).isEqualTo("first-dispatch-id");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("first-dispatch-id");
    }

    @Test
    void laterDispatchPrefersTheIdStoredOnTheRequest() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/error");
        request.setDispatcherType(DispatcherType.ERROR);
        request.setAttribute(CorrelationIds.REQUEST_ATTRIBUTE, "assigned-id");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(MdcKeys.REQUEST_ID)));

        assertThat(seen.get()).isEqualTo("assigned-id");
        assertThat(response.getHeader(RequestIdFilter.HEADER)).isEqualTo("assigned-id");
    }

    private Result run(String incoming) throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/courses");
        if (incoming != null) {
            request.addHeader(RequestIdFilter.HEADER, incoming);
        }
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seen = new AtomicReference<>();

        filter.doFilter(request, response, (req, res) -> seen.set(MDC.get(MdcKeys.REQUEST_ID)));

        assertThat(MDC.get(MdcKeys.REQUEST_ID)).as("MDC cleared after the request").isNull();
        assertThat(request.getAttribute(CorrelationIds.REQUEST_ATTRIBUTE)).isEqualTo(seen.get());
        return new Result(seen.get(), response);
    }

    private static void assertGenerated(Result result) {
        assertThat(result.idInMdc()).isNotNull();
        assertThat(UUID.fromString(result.idInMdc()).toString()).isEqualTo(result.idInMdc());
        assertThat(result.response().getHeader(RequestIdFilter.HEADER)).isEqualTo(result.idInMdc());
    }

    private record Result(String idInMdc, MockHttpServletResponse response) {
    }
}
