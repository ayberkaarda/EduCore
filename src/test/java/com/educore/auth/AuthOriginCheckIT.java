package com.educore.auth;

import com.educore.entity.Account;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** CSRF defence: the cookie-authenticated endpoints accept only allow-listed Origin/Referer values. */
class AuthOriginCheckIT extends AuthIntegrationSupport {

    private String loggedInRefreshToken(String ip) throws Exception {
        Account account = createAccount(false);
        return refreshToken(login(account.getUsername(), PASSWORD, ip));
    }

    private static MockHttpServletRequestBuilder withCookie(String path, String token, String ip) {
        return post(path).with(request -> {
            request.setRemoteAddr(ip);
            return request;
        }).cookie(new Cookie(RefreshCookies.NAME, token));
    }

    @Test
    void foreignMissingOrNullOriginIsRejectedWithoutTouchingTheToken() throws Exception {
        String ip = newIp();
        String token = loggedInRefreshToken(ip);

        for (String path : new String[]{"/api/v1/auth/refresh", "/api/v1/auth/logout"}) {
            // A foreign Origin is already refused by the CORS filter; the origin check is the second layer.
            mockMvc.perform(withCookie(path, token, ip).header(HttpHeaders.ORIGIN, "https://evil.example"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withCookie(path, token, ip).header(HttpHeaders.ORIGIN, "null"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withCookie(path, token, ip).header(HttpHeaders.REFERER, "https://evil.example/page"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/problems/auth/origin-rejected"));
            mockMvc.perform(withCookie(path, token, ip))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.type").value("/problems/auth/origin-rejected"));
            // A look-alike origin on another port or scheme is not the allowed origin.
            mockMvc.perform(withCookie(path, token, ip).header(HttpHeaders.ORIGIN, "http://localhost:3001"))
                    .andExpect(status().isForbidden());
            mockMvc.perform(withCookie(path, token, ip).header(HttpHeaders.ORIGIN, "https://localhost:3000"))
                    .andExpect(status().isForbidden());
        }

        // None of the rejected requests rotated or revoked the token.
        mockMvc.perform(withCookie("/api/v1/auth/refresh", token, ip).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isOk());
    }

    @Test
    void allowedRefererIsAcceptedWhenOriginIsAbsent() throws Exception {
        String ip = newIp();
        String token = loggedInRefreshToken(ip);

        mockMvc.perform(withCookie("/api/v1/auth/refresh", token, ip)
                        .header(HttpHeaders.REFERER, ALLOWED_ORIGIN + "/app/students?page=2"))
                .andExpect(status().isOk());
    }

    @Test
    void loginAndAllowedOriginDoNotNeedTheReferer() throws Exception {
        Account account = createAccount(false);
        String ip = newIp();

        // Login carries no cookie credential, so it is not subject to the origin check.
        String token = refreshToken(login(account.getUsername(), PASSWORD, ip));
        mockMvc.perform(withCookie("/api/v1/auth/logout", token, ip).header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isNoContent());
    }
}
