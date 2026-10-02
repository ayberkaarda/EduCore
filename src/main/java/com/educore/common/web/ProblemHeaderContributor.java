package com.educore.common.web;

import org.springframework.http.HttpHeaders;

/**
 * Lets a feature add response headers to the problem rendered for one of its {@link ApiProblemException}s
 * (for example the auth feature clears the refresh cookie) without a feature-specific exception handler.
 */
public interface ProblemHeaderContributor {

    void contribute(ApiProblemException exception, HttpHeaders headers);
}
