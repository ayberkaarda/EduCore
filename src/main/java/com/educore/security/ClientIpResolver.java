package com.educore.security;

import com.educore.config.EduCoreProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Determines the client IP used for throttling and audit records.
 * <p>
 * {@code X-Forwarded-For} is honoured only when the direct peer is listed in
 * {@code educore.ipaccess.trusted-proxies}; the result is then the right-most address in the header that is
 * not itself a trusted proxy. Otherwise the socket peer address is used, so clients cannot spoof their IP.
 */
@Component
public class ClientIpResolver {

    private static final int MAX_LENGTH = 45;

    private final Set<String> trustedProxies;

    public ClientIpResolver(EduCoreProperties properties) {
        this.trustedProxies = Set.copyOf(properties.ipaccess().trustedProxies());
    }

    public String resolve(HttpServletRequest request) {
        String peer = request.getRemoteAddr();
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && trustedProxies.contains(peer)) {
            String[] hops = forwarded.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = hops[i].trim();
                if (!hop.isEmpty() && !trustedProxies.contains(hop)) {
                    return truncate(hop);
                }
            }
        }
        return truncate(peer);
    }

    private static String truncate(String ip) {
        if (ip == null) {
            return "unknown";
        }
        return ip.length() > MAX_LENGTH ? ip.substring(0, MAX_LENGTH) : ip;
    }
}
