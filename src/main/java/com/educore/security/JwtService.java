package com.educore.security;

import com.educore.config.EduCoreProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwsHeader;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtParser;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.LocatorAdapter;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.Keys;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.Key;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Issues and verifies HS256 access tokens.
 * <p>
 * Claims: {@code iss}, {@code aud}, {@code sub} (account id), {@code jti}, {@code iat}, {@code exp}
 * (15 minutes by default), {@code roles} and {@code sep} (the account's session epoch at issuance; a token whose
 * epoch no longer matches the account is rejected by {@code JwtAuthenticationFilter}). The {@code kid} header names the signing key by a fingerprint
 * of its bytes, so a token keeps verifying after its key moves from {@code EDUCORE_JWT_SECRET} to
 * {@code EDUCORE_JWT_SECRET_PREVIOUS} during a rotation (docs/security/KEY_ROTATION.md).
 * <p>
 * Verification accepts only HS256 with a known {@code kid}, enforces issuer, audience and expiry with a
 * 30 second clock skew, requires {@code exp} and a numeric {@code sub}, and rejects tokens longer than
 * {@value #MAX_TOKEN_LENGTH} characters before parsing. Every rejection is a {@link JwtException}.
 */
@Service
public class JwtService {

    static final int MIN_SECRET_BYTES = 32;
    static final int MAX_TOKEN_LENGTH = 4096;
    static final long CLOCK_SKEW_SECONDS = 30;
    static final String ROLES_CLAIM = "roles";
    static final String SESSION_EPOCH_CLAIM = "sep";

    private static final String CURRENT_VARIABLE = "EDUCORE_JWT_SECRET";
    private static final String CURRENT_PROPERTY = "educore.security.jwt.secret";
    private static final String PREVIOUS_VARIABLE = "EDUCORE_JWT_SECRET_PREVIOUS";
    private static final String PREVIOUS_PROPERTY = "educore.security.jwt.previous-secret";

    private final SecretKey currentKey;
    private final String currentKeyId;
    private final Map<String, SecretKey> verificationKeys;
    private final String issuer;
    private final String audience;
    private final Duration accessTokenTtl;
    private final Clock clock;
    private final JwtParser parser;

    @Autowired
    public JwtService(EduCoreProperties properties, Clock clock) {
        this(properties.security().jwt(), clock);
    }

    public JwtService(EduCoreProperties.Jwt jwt, Clock clock) {
        byte[] current = decodeAndValidate(jwt.secret(), CURRENT_VARIABLE, CURRENT_PROPERTY, true);
        byte[] previous = decodeAndValidate(jwt.previousSecret(), PREVIOUS_VARIABLE, PREVIOUS_PROPERTY, false);
        this.currentKey = Keys.hmacShaKeyFor(current);
        this.currentKeyId = keyId(current);
        Map<String, SecretKey> keys = new LinkedHashMap<>();
        keys.put(currentKeyId, currentKey);
        if (previous != null && !Arrays.equals(previous, current)) {
            keys.put(keyId(previous), Keys.hmacShaKeyFor(previous));
        }
        this.verificationKeys = Map.copyOf(keys);
        this.issuer = jwt.issuer();
        this.audience = jwt.audience();
        this.accessTokenTtl = jwt.accessTokenTtl();
        this.clock = clock;
        this.parser = Jwts.parser()
                // Only HS256: tokens naming any other algorithm (HS384/HS512, none) are rejected.
                .sig().clear().add(Jwts.SIG.HS256).and()
                .keyLocator(new LocatorAdapter<Key>() {
                    @Override
                    protected Key locate(JwsHeader header) {
                        String kid = header.getKeyId();
                        SecretKey key = kid == null ? null : verificationKeys.get(kid);
                        if (key == null) {
                            throw new UnsupportedJwtException("Unknown or missing key id");
                        }
                        return key;
                    }
                })
                .requireIssuer(issuer)
                .requireAudience(audience)
                .clockSkewSeconds(CLOCK_SKEW_SECONDS)
                .clock(() -> Date.from(this.clock.instant()))
                .build();
    }

    /** Signs a new access token for {@code user} at session epoch 0 with the current key. */
    public IssuedAccessToken issue(AuthenticatedUser user) {
        return issue(user, 0);
    }

    /**
     * Signs a new access token for {@code user} with the current key, bound to {@code sessionEpoch} (the account's
     * {@code session_epoch}): the token stops authenticating as soon as the account's epoch changes.
     */
    public IssuedAccessToken issue(AuthenticatedUser user, int sessionEpoch) {
        Instant now = clock.instant();
        Instant expiresAt = now.plus(accessTokenTtl);
        String token = Jwts.builder()
                .header().keyId(currentKeyId).and()
                .issuer(issuer)
                .audience().add(audience).and()
                .subject(Long.toString(user.id()))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .claim(ROLES_CLAIM, List.of(user.role().name()))
                .claim(SESSION_EPOCH_CLAIM, sessionEpoch)
                .signWith(currentKey, Jwts.SIG.HS256)
                .compact();
        return new IssuedAccessToken(token, accessTokenTtl.toSeconds());
    }

    /**
     * Verifies {@code token} and returns its claims.
     *
     * @throws JwtException for every invalid, expired, foreign or malformed token
     */
    public AccessTokenClaims parse(String token) {
        if (token == null || token.isBlank()) {
            throw new InvalidAccessTokenException("Token is empty");
        }
        if (token.length() > MAX_TOKEN_LENGTH) {
            throw new InvalidAccessTokenException("Token exceeds " + MAX_TOKEN_LENGTH + " characters");
        }
        Claims claims;
        try {
            claims = parser.parseSignedClaims(token).getPayload();
        } catch (IllegalArgumentException e) {
            throw new InvalidAccessTokenException("Token is malformed");
        }
        if (claims.getExpiration() == null) {
            throw new InvalidAccessTokenException("Token has no exp claim");
        }
        long accountId;
        try {
            accountId = Long.parseLong(claims.getSubject());
        } catch (NumberFormatException e) {
            throw new InvalidAccessTokenException("Token subject is not an account id");
        }
        Object epoch = claims.get(SESSION_EPOCH_CLAIM);
        int sessionEpoch;
        if (epoch == null) {
            // Issued before session epochs existed (V22): such tokens belong to epoch 0.
            sessionEpoch = 0;
        } else if ((epoch instanceof Integer || epoch instanceof Long)
                && ((Number) epoch).longValue() == ((Number) epoch).intValue()) {
            sessionEpoch = ((Number) epoch).intValue();
        } else {
            throw new InvalidAccessTokenException("Token session epoch is not an integer");
        }
        return new AccessTokenClaims(accountId, claims.getId(), claims.getExpiration().toInstant(), sessionEpoch);
    }

    /** Fingerprint used as {@code kid}: the first 8 bytes of SHA-256 over the key, hex encoded. */
    static String keyId(byte[] key) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(
                    concat("educore-jwt-kid:".getBytes(StandardCharsets.US_ASCII), key));
            return HexFormat.of().formatHex(digest, 0, 8);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }

    private static byte[] concat(byte[] a, byte[] b) {
        byte[] result = Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, result, a.length, b.length);
        return result;
    }

    private static byte[] decodeAndValidate(String secret, String variable, String property, boolean required) {
        if (secret == null || secret.isBlank()) {
            if (!required) {
                return null;
            }
            throw new IllegalStateException(
                    "JWT secret is not configured: set the " + variable + " environment variable "
                            + "(property " + property + ") to a base64 value of at least "
                            + MIN_SECRET_BYTES + " bytes, e.g. generated with `openssl rand -base64 48`.");
        }
        final byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(secret.trim());
        } catch (IllegalArgumentException e) {
            // The decoder cause is deliberately not attached: its message can echo parts of the secret.
            throw new IllegalStateException(
                    "JWT secret is invalid: " + variable + " (property " + property + ") is not valid base64.");
        }
        if (decoded.length < MIN_SECRET_BYTES) {
            throw new IllegalStateException(
                    "JWT secret is too short: " + variable + " (property " + property + ") decodes to "
                            + decoded.length + " bytes; at least " + MIN_SECRET_BYTES + " bytes are required.");
        }
        return decoded;
    }

    /** A signed access token and its lifetime in seconds. */
    public record IssuedAccessToken(String token, long expiresInSeconds) {
    }

    /** Verified content of an access token. */
    public record AccessTokenClaims(long accountId, String tokenId, Instant expiresAt, int sessionEpoch) {

        /** Claims of a token at session epoch 0. */
        public AccessTokenClaims(long accountId, String tokenId, Instant expiresAt) {
            this(accountId, tokenId, expiresAt, 0);
        }
    }

    /** Raised for tokens that parse but violate a rule jjwt does not enforce by itself. */
    public static final class InvalidAccessTokenException extends JwtException {
        public InvalidAccessTokenException(String message) {
            super(message);
        }
    }
}
