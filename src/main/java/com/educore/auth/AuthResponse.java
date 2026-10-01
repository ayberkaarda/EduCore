package com.educore.auth;

/**
 * Body returned by login, refresh and password change: a bearer access token, its lifetime in seconds and
 * the signed-in user. The refresh token travels only in the {@code educore_rt} cookie.
 */
public record AuthResponse(String accessToken, long expiresIn, UserView user) {

    @Override
    public String toString() {
        return "AuthResponse[accessToken=<omitted>, expiresIn=" + expiresIn + ", user=" + user + "]";
    }
}
