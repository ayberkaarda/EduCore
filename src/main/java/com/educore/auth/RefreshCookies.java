package com.educore.auth;

import com.educore.config.EduCoreProperties;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Builds the {@code educore_rt} refresh cookie: {@code HttpOnly}, {@code SameSite=Strict},
 * {@code Path=/api/v1/auth}, {@code Max-Age} equal to the refresh token lifetime (14 days), and
 * {@code Secure} unless {@code educore.security.refresh-token.cookie-secure=false} (dev profile only).
 */
@Component
public class RefreshCookies {

    public static final String NAME = "educore_rt";
    public static final String PATH = "/api/v1/auth";

    private final boolean secure;
    private final Duration maxAge;

    public RefreshCookies(EduCoreProperties properties) {
        this.secure = properties.security().refreshToken().cookieSecure();
        this.maxAge = properties.security().refreshToken().ttl();
    }

    public ResponseCookie issue(String value) {
        return base(value).maxAge(maxAge).build();
    }

    public ResponseCookie clear() {
        return base("").maxAge(Duration.ZERO).build();
    }

    private ResponseCookie.ResponseCookieBuilder base(String value) {
        return ResponseCookie.from(NAME, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path(PATH);
    }
}
