package com.educore.auth;

import com.educore.common.web.ApiProblemException;
import com.educore.common.web.ProblemHeaderContributor;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;

/** Clears the refresh cookie on the problems that end the session (an invalid or reused refresh token). */
@Component
class RefreshCookieProblemHeaders implements ProblemHeaderContributor {

    private final RefreshCookies refreshCookies;

    RefreshCookieProblemHeaders(RefreshCookies refreshCookies) {
        this.refreshCookies = refreshCookies;
    }

    @Override
    public void contribute(ApiProblemException exception, HttpHeaders headers) {
        if (exception instanceof AuthProblemException auth && auth.clearRefreshCookie()) {
            headers.add(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString());
        }
    }
}
