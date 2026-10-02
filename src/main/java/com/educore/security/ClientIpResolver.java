package com.educore.security;

import com.educore.ipaccess.ClientAddress;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Determines the client address used for IP access control, rate limiting, throttling and audit records.
 * <p>
 * {@code X-Forwarded-For} is honoured only when the direct peer lies inside {@code educore.ipaccess.trusted-proxies}
 * ({@link TrustedProxies}); the result is then the right-most hop that is not itself a trusted proxy (the
 * address the outermost trusted proxy saw). Several {@code X-Forwarded-For} header lines are read in order as
 * one list, the way Tomcat's {@code RemoteIpValve} combines them. Hops left of the chosen one are
 * client-supplied and ignored. When that hop is not an IP address (only a misbehaving trusted proxy can
 * produce this) it is returned as an invalid {@link ClientAddress}, exactly as Tomcat's valve would set it,
 * and {@code IpAccessControlFilter} refuses the request. Without a trusted peer the socket peer address is
 * used, so a client cannot choose its own key by sending the header.
 * <p>
 * Every address is normalised by {@link ClientAddress}: an IPv4-mapped IPv6 address or an {@code ip:port}
 * becomes the plain dotted quad, so trust, deny and rate-limit decisions see the same text. Behind Tomcat's
 * {@code RemoteIpValve} ({@code server.forward-headers-strategy=native}, configured with the same trusted ranges
 * by {@code ForwardedHeadersConfig}) the peer has already been replaced by that hop, and applying this rule
 * again yields the same result.
 */
@Component
public class ClientIpResolver {

    public static final String FORWARDED_FOR = "X-Forwarded-For";
    /** At most this many hops are examined; a longer header is treated as unusable. */
    private static final int MAX_HOPS = 32;

    private final TrustedProxies trustedProxies;

    public ClientIpResolver(TrustedProxies trustedProxies) {
        this.trustedProxies = trustedProxies;
    }

    /** The normalised client address text (see {@link ClientAddress#canonical()}). */
    public String resolve(HttpServletRequest request) {
        return resolveAddress(request).canonical();
    }

    public ClientAddress resolveAddress(HttpServletRequest request) {
        ClientAddress peer = ClientAddress.parse(request.getRemoteAddr());
        if (!trustedProxies.contains(peer)) {
            return peer;
        }
        List<String> hops = forwardedHops(request);
        if (hops.size() > MAX_HOPS) {
            return peer;
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i).trim();
            if (hop.isEmpty()) {
                continue;
            }
            ClientAddress address = ClientAddress.parse(hop);
            if (!trustedProxies.contains(address)) {
                return address;
            }
        }
        return peer;
    }

    private static List<String> forwardedHops(HttpServletRequest request) {
        Enumeration<String> headers = request.getHeaders(FORWARDED_FOR);
        if (headers == null) {
            return List.of();
        }
        List<String> hops = new ArrayList<>();
        for (String header : Collections.list(headers)) {
            for (String hop : header.split(",", MAX_HOPS + 2)) {
                hops.add(hop);
            }
        }
        return hops;
    }
}
