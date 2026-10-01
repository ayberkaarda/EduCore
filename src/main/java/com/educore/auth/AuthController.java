package com.educore.auth;

import com.educore.security.AuthenticatedUser;
import com.educore.security.ClientIpResolver;
import com.educore.security.OriginVerifier;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CookieValue;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Authentication endpoints under {@code /api/v1/auth}.
 * <ul>
 *   <li>{@code POST /login}: username and password; returns the access token and sets the refresh cookie.</li>
 *   <li>{@code POST /refresh}: refresh cookie only; rotates it and returns a new access token.</li>
 *   <li>{@code POST /logout}: revokes the refresh token family and clears the cookie.</li>
 *   <li>{@code GET /me}: the signed-in user (bearer token).</li>
 *   <li>{@code POST /password}: changes the password (bearer token); revokes all refresh tokens and starts a
 *       new session.</li>
 * </ul>
 * {@code /refresh} and {@code /logout} are cookie-authenticated and therefore require an allowed
 * {@code Origin}/{@code Referer} ({@link OriginVerifier}).
 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final RefreshCookies refreshCookies;
    private final ClientIpResolver clientIpResolver;
    private final OriginVerifier originVerifier;

    public AuthController(AuthService authService, RefreshCookies refreshCookies, ClientIpResolver clientIpResolver,
                          OriginVerifier originVerifier) {
        this.authService = authService;
        this.refreshCookies = refreshCookies;
        this.clientIpResolver = clientIpResolver;
        this.originVerifier = originVerifier;
    }

    @PostMapping("/login")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        return sessionResponse(authService.login(request, client(http)));
    }

    @PostMapping("/refresh")
    public ResponseEntity<AuthResponse> refresh(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken, HttpServletRequest http) {
        requireAllowedOrigin(http);
        return sessionResponse(authService.refresh(refreshToken, client(http)));
    }

    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @CookieValue(name = RefreshCookies.NAME, required = false) String refreshToken, HttpServletRequest http) {
        requireAllowedOrigin(http);
        authService.logout(refreshToken);
        return ResponseEntity.noContent()
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear().toString())
                .build();
    }

    @GetMapping("/me")
    public ResponseEntity<UserView> me(@AuthenticationPrincipal AuthenticatedUser user) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(authService.me(user));
    }

    @PostMapping("/password")
    public ResponseEntity<AuthResponse> changePassword(@AuthenticationPrincipal AuthenticatedUser user,
                                                       @Valid @RequestBody PasswordChangeRequest request,
                                                       HttpServletRequest http) {
        return sessionResponse(authService.changePassword(user, request, client(http)));
    }

    private ResponseEntity<AuthResponse> sessionResponse(AuthService.Session session) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header(HttpHeaders.SET_COOKIE, refreshCookies.issue(session.refreshToken()).toString())
                .body(session.body());
    }

    private void requireAllowedOrigin(HttpServletRequest http) {
        if (!originVerifier.isAllowed(http)) {
            throw AuthProblemException.originRejected();
        }
    }

    private ClientInfo client(HttpServletRequest http) {
        return new ClientInfo(clientIpResolver.resolve(http), http.getHeader(HttpHeaders.USER_AGENT));
    }
}
