package com.educore.auth;

import com.educore.config.EduCoreProperties;
import com.educore.security.audit.AuditService;
import com.educore.security.audit.SecurityEventType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Opaque refresh tokens: 256 random bits, base64url encoded, stored as SHA-256 hex.
 * <p>
 * Each successful refresh revokes the presented token and issues its successor in the same family
 * ({@code replaced_by} links them). Presenting a token that is already revoked means it was copied: the
 * whole family is revoked and {@code AUTH_REFRESH_REUSE} is recorded, in the same transaction.
 * <p>
 * Concurrency: every operation on a family (rotation, reuse revocation, logout, password change) first
 * locks the family's {@code refresh_token_family} row and only then reads or revokes its tokens. Rotation
 * and revocation of one family are therefore strictly serialised: a revocation either runs before the
 * rotation (which then sees the revoked family or token and fails) or after it (and then revokes the
 * committed successor too). A revoked family is never usable again.
 */
@Service
public class RefreshTokenService {

    private static final int TOKEN_BYTES = 32;
    private static final Pattern TOKEN_FORMAT = Pattern.compile("[A-Za-z0-9_-]{43}");

    private final RefreshTokenRepository repository;
    private final RefreshTokenFamilyRepository families;
    private final AuditService events;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom random = new SecureRandom();

    public RefreshTokenService(RefreshTokenRepository repository, RefreshTokenFamilyRepository families,
                               AuditService events, Clock clock, EduCoreProperties properties) {
        this.repository = repository;
        this.families = families;
        this.events = events;
        this.clock = clock;
        this.ttl = properties.security().refreshToken().ttl();
    }

    /** Outcome of {@link #rotate}. */
    public sealed interface Rotation {
        /** The token was valid; {@code value} is its successor. */
        record Rotated(long accountId, String value) implements Rotation {
        }

        /** Unknown, malformed or expired token, or a token of a revoked family. */
        record Rejected() implements Rotation {
        }

        /** A revoked token was presented; its family is now revoked. */
        record Reused(long accountId) implements Rotation {
        }
    }

    /** Starts a new family for {@code accountId} and returns the token value for the cookie. */
    @Transactional
    public String issueNewFamily(long accountId, ClientInfo client) {
        Instant now = clock.instant();
        RefreshTokenFamily family = families.saveAndFlush(new RefreshTokenFamily(UUID.randomUUID(), accountId, now));
        return issue(accountId, family.getId(), client, now).value();
    }

    @Transactional
    public Rotation rotate(String value, ClientInfo client) {
        if (!isWellFormed(value)) {
            return new Rotation.Rejected();
        }
        String tokenHash = hash(value);
        Optional<RefreshTokenFamily> lockedFamily = repository.findFamilyIdByTokenHash(tokenHash)
                .flatMap(families::findByIdForUpdate);
        if (lockedFamily.isEmpty()) {
            return new Rotation.Rejected();
        }
        RefreshTokenFamily family = lockedFamily.get();
        // Read after the family lock: the state is whatever the previous holder of the lock committed.
        Optional<RefreshToken> found = repository.findByTokenHashForUpdate(tokenHash);
        if (found.isEmpty()) {
            return new Rotation.Rejected();
        }
        RefreshToken token = found.get();
        Instant now = clock.instant();
        if (token.getRevokedAt() != null) {
            family.revoke(now);
            int revoked = repository.revokeFamily(family.getId(), now);
            events.record(SecurityEventType.AUTH_REFRESH_REUSE, null, token.getAccountId(), client.ip(),
                    Map.of("familyId", family.getId().toString(), "revokedTokens", revoked));
            return new Rotation.Reused(token.getAccountId());
        }
        if (family.isRevoked() || !token.getExpiresAt().isAfter(now)) {
            return new Rotation.Rejected();
        }
        Issued successor = issue(token.getAccountId(), family.getId(), client, now);
        token.revoke(now, successor.token().getId());
        return new Rotation.Rotated(token.getAccountId(), successor.value());
    }

    /** Revokes the family of {@code value} (logout). Unknown values are ignored. */
    @Transactional
    public void revokeFamilyOf(String value) {
        if (!isWellFormed(value)) {
            return;
        }
        repository.findFamilyIdByTokenHash(hash(value))
                .flatMap(families::findByIdForUpdate)
                .ifPresent(family -> {
                    Instant now = clock.instant();
                    family.revoke(now);
                    repository.revokeFamily(family.getId(), now);
                });
    }

    /**
     * Revokes every family and active refresh token of {@code accountId} (password change). Revoking the
     * families first locks them, so rotations in flight finish (and their successors are revoked by the
     * second statement) and later rotations find the family revoked. Callers hold the account lock, so no
     * new family can be started concurrently.
     */
    @Transactional
    public int revokeAll(long accountId) {
        Instant now = clock.instant();
        families.revokeAllForAccount(accountId, now);
        return repository.revokeAllForAccount(accountId, now);
    }

    private Issued issue(long accountId, UUID familyId, ClientInfo client, Instant now) {
        byte[] raw = new byte[TOKEN_BYTES];
        random.nextBytes(raw);
        String value = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        RefreshToken token = repository.save(new RefreshToken(accountId, hash(value), familyId, now, now.plus(ttl),
                client.userAgent(), client.ip()));
        return new Issued(value, token);
    }

    private static boolean isWellFormed(String value) {
        return value != null && TOKEN_FORMAT.matcher(value).matches();
    }

    static String hash(String value) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private record Issued(String value, RefreshToken token) {
    }
}
