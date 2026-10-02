package com.educore.security;

import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import java.util.List;

/**
 * Authentication established from a valid bearer access token. The principal is the typed
 * {@link AuthenticatedUser} (never the {@code Account} entity). An active account has the single authority
 * {@code ROLE_<role>} with the role read from the database for this request. Two restricted scopes carry a single
 * non-role authority instead, so every role check denies them even where their scope filter does not apply:
 * <ul>
 *   <li>{@link #pendingDeletion}: an account in its deletion grace period ({@link #PENDING_DELETION_AUTHORITY},
 *       enforced by {@code PendingDeletionScopeFilter});</li>
 *   <li>{@link #passwordChangeRequired}: an active account that must change its password first
 *       ({@link #PASSWORD_CHANGE_REQUIRED_AUTHORITY}, enforced by {@code PasswordChangeRequiredScopeFilter}).</li>
 * </ul>
 * {@link #getName()} returns the username.
 */
public final class AccessTokenAuthentication extends AbstractAuthenticationToken {

    /** The only authority of an account whose deletion is pending. */
    public static final String PENDING_DELETION_AUTHORITY = "ACCOUNT_PENDING_DELETION";
    /** The only authority of an account that must change its password before anything else. */
    public static final String PASSWORD_CHANGE_REQUIRED_AUTHORITY = "ACCOUNT_PASSWORD_CHANGE_REQUIRED";

    /** What the token may do. */
    public enum Scope {
        /** Everything the role allows. */
        FULL,
        /** Restore-only scope of the deletion grace period. */
        PENDING_DELETION,
        /** Only reading the signed-in user, changing the password, refreshing and logging out. */
        PASSWORD_CHANGE_REQUIRED
    }

    private final AuthenticatedUser user;
    private final Scope scope;

    public AccessTokenAuthentication(AuthenticatedUser user) {
        this(user, Scope.FULL);
    }

    private AccessTokenAuthentication(AuthenticatedUser user, Scope scope) {
        super(authoritiesFor(user, scope));
        this.user = user;
        this.scope = scope;
        setAuthenticated(true);
    }

    /** The restore-only authentication of an account in its deletion grace period. */
    public static AccessTokenAuthentication pendingDeletion(AuthenticatedUser user) {
        return new AccessTokenAuthentication(user, Scope.PENDING_DELETION);
    }

    /** The authentication of an active account flagged {@code mustChangePassword}. */
    public static AccessTokenAuthentication passwordChangeRequired(AuthenticatedUser user) {
        return new AccessTokenAuthentication(user, Scope.PASSWORD_CHANGE_REQUIRED);
    }

    private static List<GrantedAuthority> authoritiesFor(AuthenticatedUser user, Scope scope) {
        String authority = switch (scope) {
            case FULL -> "ROLE_" + user.role().name();
            case PENDING_DELETION -> PENDING_DELETION_AUTHORITY;
            case PASSWORD_CHANGE_REQUIRED -> PASSWORD_CHANGE_REQUIRED_AUTHORITY;
        };
        return List.of(new SimpleGrantedAuthority(authority));
    }

    public Scope scope() {
        return scope;
    }

    /** Whether the caller's account is pending deletion (restore-only scope). */
    public boolean isPendingDeletion() {
        return scope == Scope.PENDING_DELETION;
    }

    /** Whether the caller must change the password before using anything else. */
    public boolean isPasswordChangeRequired() {
        return scope == Scope.PASSWORD_CHANGE_REQUIRED;
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
