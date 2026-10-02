package com.educore.common.web;

import com.educore.config.EduCoreProperties;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProblemDetailsAdviceTest {

    private final ProblemDetailsAdvice advice = new ProblemDetailsAdvice(problems("/problems/"),
            List.of((exception, headers) -> headers.add("X-Contributed", exception.code())));
    private final MockHttpServletRequest request = new MockHttpServletRequest("PUT", "/api/v1/admin/accounts/7");

    @Test
    void apiProblemKeepsStatusCodeTitleAndGetsContributedHeaders() {
        ResponseEntity<ProblemDetail> response = advice.handleApiProblem(
                ApiProblemException.conflict("account/last-admin", "The last active administrator cannot be removed."),
                request);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getHeaders().getFirst("X-Contributed")).isEqualTo("account/last-admin");
        ProblemDetail body = response.getBody();
        assertThat(body.getType()).isEqualTo(URI.create("/problems/account/last-admin"));
        assertThat(body.getTitle()).isEqualTo("The last active administrator cannot be removed.");
        assertThat(body.getInstance()).isEqualTo(URI.create("/api/v1/admin/accounts/7"));
        assertThat(body.getProperties()).containsEntry("code", "account/last-admin");
    }

    @Test
    void retryAfterIsRoundedUpToWholeSeconds() {
        ApiProblemException locked = new ApiProblemException(HttpStatus.LOCKED, "auth/account-locked", "Locked",
                Duration.ofMillis(1500), Map.of());

        assertThat(advice.handleApiProblem(locked, request).getHeaders().getFirst(HttpHeaders.RETRY_AFTER))
                .isEqualTo("2");
        assertThat(ProblemDetailsAdvice.retryAfterSeconds(Duration.ZERO)).isEqualTo(1);
        assertThat(ProblemDetailsAdvice.retryAfterSeconds(Duration.ofSeconds(900))).isEqualTo(900);
    }

    @Test
    void conflictsUseStableCodesAndNoExceptionText() {
        ProblemDetail optimistic = advice.handleOptimisticLock(request).getBody();
        ProblemDetail integrity = advice.handleDataIntegrity(request).getBody();

        assertThat(optimistic.getType()).isEqualTo(URI.create("/problems/request/concurrent-modification"));
        assertThat(integrity.getType()).isEqualTo(URI.create("/problems/request/conflict"));
        assertThat(integrity.getStatus()).isEqualTo(409);
        assertThat(optimistic.getDetail()).isEqualTo(integrity.getDetail()).isNotBlank();
    }

    @Test
    void unexpectedExceptionIsA500WithOnlyTheRequestIdAsCorrelationId() {
        MDC.put("requestId", "unit-correlation-1");
        try {
            ResponseEntity<ProblemDetail> response = advice.handleUnexpected(
                    new IllegalStateException("password=hunter2 at com.educore.Secret"), request,
                    new MockHttpServletResponse());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            ProblemDetail body = response.getBody();
            assertThat(body.getType()).isEqualTo(URI.create("/problems/server/internal-error"));
            assertThat(body.getProperties()).containsEntry("correlationId", "unit-correlation-1")
                    .containsOnlyKeys("code", "correlationId");
            assertThat(String.valueOf(body.getTitle()) + body.getDetail()).doesNotContain("hunter2", "Secret");
        } finally {
            MDC.remove("requestId");
        }
    }

    @Test
    void correlationIdPrefersTheRequestAttributeThenTheResponseHeaderOverTheThreadMdc() {
        MDC.put("requestId", "stale-thread-value");
        try {
            MockHttpServletResponse response = new MockHttpServletResponse();
            response.setHeader("X-Request-Id", "from-header");
            assertThat(CorrelationIds.of(request, response)).isEqualTo("from-header");

            request.setAttribute(CorrelationIds.REQUEST_ATTRIBUTE, "from-attribute");
            assertThat(CorrelationIds.of(request, response)).isEqualTo("from-attribute");
            assertThat(advice.handleUnexpected(new IllegalStateException("x"), request, response).getBody()
                    .getProperties()).containsEntry("correlationId", "from-attribute");
        } finally {
            MDC.remove("requestId");
        }
        MockHttpServletResponse fresh = new MockHttpServletResponse();
        String generated = CorrelationIds.of(new MockHttpServletRequest(), fresh);
        assertThat(fresh.getHeader("X-Request-Id")).isEqualTo(generated);
    }

    @Test
    void bodyTooLargeAnywhereInTheCauseChainIsA413() {
        Exception wrapped = new IllegalStateException("read failed",
                new java.io.UncheckedIOException(new RequestBodyTooLargeException()));

        ResponseEntity<ProblemDetail> response = advice.handleUnexpected(wrapped, request, new MockHttpServletResponse());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        assertThat(response.getBody().getProperties()).containsEntry("code", "request/payload-too-large");
    }

    @Test
    void instanceDropsCharactersThatAreNotLegalInAUriPath() {
        assertThat(Problems.safePath("/api/v1/x%zz<script>\"y\"%2F")).isEqualTo("/api/v1/xzzscripty%2F");
        assertThat(Problems.safePath("")).isEqualTo("/");
    }

    @Test
    void constraintNamesMapToStableCodes() {
        assertThat(ValidationErrors.code("NotBlank")).isEqualTo("required");
        assertThat(ValidationErrors.code("Size")).isEqualTo("size");
        assertThat(ValidationErrors.code("Pattern")).isEqualTo("pattern");
        assertThat(ValidationErrors.code("Positive")).isEqualTo("range");
        assertThat(ValidationErrors.code("typeMismatch")).isEqualTo("type");
        assertThat(ValidationErrors.code("SomethingCustom")).isEqualTo("invalid");
    }

    private static Problems problems(String base) {
        EduCoreProperties properties = mock(EduCoreProperties.class);
        when(properties.problems()).thenReturn(new EduCoreProperties.Problems(URI.create(base)));
        return new Problems(properties);
    }
}
