package com.educore.security;

import com.educore.config.EduCoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * CSRF defence for the cookie-authenticated endpoints ({@code /api/v1/auth/refresh} and
 * {@code /api/v1/auth/logout}), in addition to {@code SameSite=Strict} on the refresh cookie.
 * <p>
 * A request passes when its {@code Origin} header, or, when the browser sent no {@code Origin}, the origin of
 * its {@code Referer}, is one of the allowed origins: {@code educore.cors.allowed-origins} plus the origin of
 * {@code educore.seo.base-url} (the site the SPA is served from; in {@code prod} the CORS list is empty
 * because the SPA and the API share that origin). Requests with neither header, or with {@code Origin: null},
 * are rejected.
 * <p>
 * Every other API endpoint authenticates with the {@code Authorization: Bearer} header, which a browser
 * never attaches on its own to a cross-site request, so Spring Security's CSRF tokens stay disabled for them.
 */
@Component
public class OriginVerifier {

    private final Set<String> allowedOrigins;

    public OriginVerifier(EduCoreProperties properties) {
        Set<String> allowed = new LinkedHashSet<>();
        for (String origin : properties.cors().allowedOrigins()) {
            String normalized = normalize(origin);
            if (normalized != null) {
                allowed.add(normalized);
            }
        }
        String siteOrigin = normalize(properties.seo().baseUrl().toString());
        if (siteOrigin != null) {
            allowed.add(siteOrigin);
        }
        this.allowedOrigins = Set.copyOf(allowed);
    }

    public boolean isAllowed(HttpServletRequest request) {
        String origin = request.getHeader("Origin");
        if (origin != null) {
            String normalized = normalize(origin);
            return normalized != null && allowedOrigins.contains(normalized);
        }
        String referer = request.getHeader("Referer");
        if (referer != null) {
            String normalized = normalize(referer);
            return normalized != null && allowedOrigins.contains(normalized);
        }
        return false;
    }

    /** {@code scheme://host[:port]} in lower case with default ports dropped, or {@code null} if unparsable. */
    static String normalize(String value) {
        if (value == null || value.isBlank() || "null".equals(value.trim())) {
            return null;
        }
        try {
            URI uri = URI.create(value.trim());
            String scheme = uri.getScheme();
            String host = uri.getHost();
            if (scheme == null || host == null) {
                return null;
            }
            scheme = scheme.toLowerCase(Locale.ROOT);
            if (!scheme.equals("http") && !scheme.equals("https")) {
                return null;
            }
            int port = uri.getPort();
            boolean defaultPort = port == -1
                    || (scheme.equals("http") && port == 80)
                    || (scheme.equals("https") && port == 443);
            return scheme + "://" + host.toLowerCase(Locale.ROOT) + (defaultPort ? "" : ":" + port);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
