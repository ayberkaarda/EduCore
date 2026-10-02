package com.educore.webhook;

import com.educore.common.web.ApiProblemException;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Validates a webhook URL when a subscription is created or changed: absolute {@code https} URL with a host,
 * no user info, no fragment, at most 2048 characters, and neither a blocked host name nor a blocked IP
 * literal. Host names are not resolved here (DNS can change); the dispatcher checks the resolved addresses
 * at send time.
 */
final class WebhookUrls {

    static final String INVALID = "webhook/invalid-url";
    static final int MAX_LENGTH = 2048;

    private WebhookUrls() {
    }

    static URI validate(String raw, WebhookAddressPolicy policy) {
        if (raw == null || raw.length() > MAX_LENGTH) {
            throw invalid();
        }
        URI uri;
        try {
            uri = new URI(raw.trim());
        } catch (URISyntaxException e) {
            throw invalid();
        }
        if (!uri.isAbsolute() || !"https".equals(uri.getScheme().toLowerCase(Locale.ROOT)) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getRawFragment() != null) {
            throw invalid();
        }
        String host = uri.getHost();
        if (policy.isBlockedHostName(host)) {
            throw invalid();
        }
        if (isIpLiteral(host)) {
            try {
                if (!policy.isAllowed(InetAddress.getByName(host))) {
                    throw invalid();
                }
            } catch (UnknownHostException e) {
                throw invalid();
            }
        }
        return uri.normalize();
    }

    /** IPv4 dotted quad or bracketed IPv6; {@code InetAddress.getByName} does not query DNS for these. */
    private static boolean isIpLiteral(String host) {
        return host.startsWith("[") || host.matches("[0-9.]+");
    }

    private static ApiProblemException invalid() {
        return ApiProblemException.badRequest(INVALID, "The webhook URL must be a public https URL.");
    }
}
