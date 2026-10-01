package com.educore.auth;

/** Client attributes recorded with tokens, attempts and security events. */
public record ClientInfo(String ip, String userAgent) {

    private static final int MAX_USER_AGENT = 512;

    public ClientInfo {
        if (userAgent != null && userAgent.length() > MAX_USER_AGENT) {
            userAgent = userAgent.substring(0, MAX_USER_AGENT);
        }
    }
}
