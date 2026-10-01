package com.educore.common.web;

import com.educore.config.EduCoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = handler();

    @Test
    void optimisticLockConflictIsA409ProblemWithoutInternals() {
        ResponseEntity<ProblemDetail> response = handler.concurrentModification();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(response.getBody().getType()).isEqualTo(URI.create("/problems/request/concurrent-modification"));
        assertThat(response.getBody().getDetail()).isNull();
    }

    @Test
    void apiProblemKeepsItsStatusCodeAndTitle() {
        ResponseEntity<ProblemDetail> response = handler.handle(
                ApiProblemException.conflict("account/last-admin", "The last active administrator cannot be removed."));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().getType()).isEqualTo(URI.create("/problems/account/last-admin"));
        assertThat(response.getBody().getTitle()).isEqualTo("The last active administrator cannot be removed.");
    }

    private static ApiExceptionHandler handler() {
        EduCoreProperties properties = mock(EduCoreProperties.class);
        when(properties.problems()).thenReturn(new EduCoreProperties.Problems(URI.create("/problems")));
        return new ApiExceptionHandler(properties);
    }
}
