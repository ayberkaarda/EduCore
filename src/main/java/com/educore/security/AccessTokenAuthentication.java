package com.educore.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authentication established from a valid bearer access token. The principal is the typed
 * {@link AuthenticatedUser} (never the {@code Account} entity); the single authority is
 * {@code ROLE_<role>} with the role read from the database for this request. {@link #getName()} returns the
 * username.
 */
public final class AccessTokenAuthentication extends AbstractAuthenticationToken {

    private final AuthenticatedUser user;

    public AccessTokenAuthentication(AuthenticatedUser user) {
        super(List.of(new SimpleGrantedAuthority("ROLE_" + user.role().name())));
        this.user = user;
        setAuthenticated(true);
    }

    @Override
    public AuthenticatedUser getPrincipal() {
        return user;
    }

    @Override
    public Object getCredentials() {
        return null;
    }

    @Override
    public String getName() {
        return user.username();
    }
}
