package com.educore.security;

import com.educore.config.EduCoreProperties;
import com.educore.entity.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.IncorrectClaimException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.MacAlgorithm;
import io.jsonwebtoken.security.SignatureException;
import org.junit.jupiter.api.Test;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Access token issuing and verification rules of {@link JwtService}. */
class JwtParserTest {

    private static final AuthenticatedUser USER = new AuthenticatedUser(42L, "demo", Role.USER);
    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");

    private final byte[] keyBytes = randomKey(64);
    private final SecretKey key = Keys.hmacShaKeyFor(keyBytes);
    private final String kid = JwtService.keyId(keyBytes);
    private final MutableClock clock = new MutableClock(T0);
    private final JwtService service = service(keyBytes, null);

    private static byte[] randomKey(int bytes) {
        byte[] raw = new byte[bytes];
        new SecureRandom().nextBytes(raw);
        return raw;
    }

    private JwtService service(byte[] current, byte[] previous) {
        Base64.Encoder b64 = Base64.getEncoder();
        return new JwtService(new EduCoreProperties.Jwt(b64.encodeToString(current),
                previous == null ? null : b64.encodeToString(previous),
                "educore", "educore-api", Duration.ofMinutes(15)), clock);
    }

    /** A token with every claim the service issues, signed with the test key; tests remove or alter parts. */
    private JwtBuilder validBuilder() {
        return Jwts.builder()
                .header().keyId(kid).and()
                .issuer("educore")
                .audience().add("educore-api").and()
                .subject("42")
                .id("test-jti")
                .issuedAt(Date.from(T0))
                .expiration(Date.from(T0.plus(Duration.ofMinutes(15))))
                .claim("roles", List.of("USER"));
    }

    @Test
    void issuedTokenCarriesTheRequiredClaimsAndVerifies() {
        JwtService.IssuedAccessToken issued = service.issue(USER);

        assertThat(issued.expiresInSeconds()).isEqualTo(900);
        JwtService.AccessTokenClaims claims = service.parse(issued.token());
        assertThat(claims.accountId()).isEqualTo(42L);
        assertThat(claims.tokenId()).isNotBlank();
        assertThat(claims.expiresAt()).isEqualTo(T0.plus(Duration.ofMinutes(15)));

        var jws = Jwts.parser().verifyWith(key).clock(() -> Date.from(T0)).build().parseSignedClaims(issued.token());
        Claims raw = jws.getPayload();
        assertThat(jws.getHeader().getAlgorithm()).isEqualTo("HS256");
        assertThat(jws.getHeader().getKeyId()).isEqualTo(kid);
        assertThat(raw.getIssuer()).isEqualTo("educore");
        assertThat(raw.getAudience()).containsExactly("educore-api");
        assertThat(raw.getSubject()).isEqualTo("42");
        assertThat(raw.get("roles", List.class)).containsExactly("USER");
        assertThat(raw.getIssuedAt()).isNotNull();
    }

    /** The session epoch (V22) travels in {@code sep}; tokens from before session epochs count as epoch 0. */
    @Test
    void theSessionEpochRoundTripsAndDefaultsToZero() {
        assertThat(service.parse(service.issue(USER, 7).token()).sessionEpoch()).isEqualTo(7);
        assertThat(service.parse(service.issue(USER).token()).sessionEpoch()).isZero();
        String legacy = validBuilder().header().keyId(kid).and().signWith(key, Jwts.SIG.HS256).compact();
        assertThat(service.parse(legacy).sessionEpoch()).isZero();
        String text = validBuilder().claim("sep", "1").header().keyId(kid).and().signWith(key, Jwts.SIG.HS256)
                .compact();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> service.parse(text))
                .isInstanceOf(io.jsonwebtoken.JwtException.class);
    }

    @Test
    void eachTokenHasAUniqueJti() {
        assertThat(service.parse(service.issue(USER).token()).tokenId())
                .isNotEqualTo(service.parse(service.issue(USER).token()).tokenId());
    }

    @Test
    void expiredTokenIsRejectedAfterTheClockSkew() {
        String token = service.issue(USER).token();

        clock.set(T0.plus(Duration.ofMinutes(15)).plusSeconds(20));
        assertThat(service.parse(token).accountId()).isEqualTo(42L);

        clock.set(T0.plus(Duration.ofMinutes(15)).plusSeconds(31));
        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }

    @Test
    void tamperedPayloadIsRejected() {
        String[] parts = service.issue(USER).token().split("\\.");
        String forgedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)
                        .replace("\"sub\":\"42\"", "\"sub\":\"1\"").getBytes(StandardCharsets.UTF_8));
        assertThat(forgedPayload).isNotEqualTo(parts[1]);

        assertThatThrownBy(() -> service.parse(parts[0] + "." + forgedPayload + "." + parts[2]))
                .isInstanceOf(SignatureException.class);
    }

    @Test
    void tamperedSignatureIsRejected() {
        String token = service.issue(USER).token();
        char last = token.charAt(token.length() - 2);
        String tampered = token.substring(0, token.length() - 2) + (last == 'A' ? 'B' : 'A')
                + token.charAt(token.length() - 1);

        assertThatThrownBy(() -> service.parse(tampered)).isInstanceOf(JwtException.class);
    }

    @Test
    void unsignedAlgNoneTokenIsRejected() {
        String unsigned = Jwts.builder().header().keyId(kid).and()
                .issuer("educore").audience().add("educore-api").and().subject("42")
                .expiration(Date.from(T0.plusSeconds(600))).compact();

        assertThat(unsigned).endsWith(".");
        assertThatThrownBy(() -> service.parse(unsigned)).isInstanceOf(JwtException.class);
    }

    @Test
    void otherHmacAlgorithmsAreRejectedEvenWithTheSameKey() {
        for (MacAlgorithm algorithm : List.of(Jwts.SIG.HS384, Jwts.SIG.HS512)) {
            String token = validBuilder().signWith(key, algorithm).compact();
            assertThatThrownBy(() -> service.parse(token)).as(algorithm.getId()).isInstanceOf(JwtException.class);
        }
        assertThat(service.parse(validBuilder().signWith(key, Jwts.SIG.HS256).compact()).accountId()).isEqualTo(42L);
    }

    @Test
    void wrongAudienceIsRejected() {
        String token = validBuilder().audience().single("another-api").signWith(key, Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(IncorrectClaimException.class);
    }

    @Test
    void wrongIssuerIsRejected() {
        String token = validBuilder().issuer("someone-else").signWith(key, Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(IncorrectClaimException.class);
    }

    @Test
    void tokenWithoutExpIsRejected() {
        String token = validBuilder().expiration(null).signWith(key, Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> service.parse(token))
                .isInstanceOf(JwtService.InvalidAccessTokenException.class)
                .hasMessageContaining("exp");
    }

    @Test
    void nonNumericSubjectIsRejected() {
        String token = validBuilder().subject("admin").signWith(key, Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(JwtService.InvalidAccessTokenException.class);
    }

    @Test
    void oversizedTokenIsRejectedBeforeParsing() {
        String oversized = validBuilder().claim("padding", "x".repeat(5000)).signWith(key, Jwts.SIG.HS256).compact();

        assertThat(oversized.length()).isGreaterThan(JwtService.MAX_TOKEN_LENGTH);
        assertThatThrownBy(() -> service.parse(oversized))
                .isInstanceOf(JwtService.InvalidAccessTokenException.class)
                .hasMessageContaining("exceeds");
    }

    @Test
    void malformedValuesAreRejectedAsJwtExceptions() {
        for (String garbage : List.of("", "   ", "abc", "a.b.c", "a.b", "....", "eyJhbGciOiJIUzI1NiJ9.e30.")) {
            assertThatThrownBy(() -> service.parse(garbage)).as("'%s'", garbage).isInstanceOf(JwtException.class);
        }
    }

    @Test
    void missingOrUnknownKidIsRejected() {
        String noKid = Jwts.builder().issuer("educore").audience().add("educore-api").and().subject("42")
                .expiration(Date.from(T0.plusSeconds(600))).signWith(key, Jwts.SIG.HS256).compact();
        String unknownKid = validBuilder().header().keyId("0000000000000000").and()
                .signWith(key, Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> service.parse(noKid)).isInstanceOf(JwtException.class);
        assertThatThrownBy(() -> service.parse(unknownKid)).isInstanceOf(JwtException.class);
    }

    @Test
    void kidRotationKeepsPreviousKeyTokensValidUntilThePreviousKeyIsRemoved() {
        byte[] newKey = randomKey(48);
        String oldToken = service.issue(USER).token();

        JwtService rotated = service(newKey, keyBytes);
        assertThat(rotated.parse(oldToken).accountId()).isEqualTo(42L);
        String newToken = rotated.issue(USER).token();
        String newKid = Jwts.parser().verifyWith(Keys.hmacShaKeyFor(newKey)).clock(() -> Date.from(T0)).build()
                .parseSignedClaims(newToken).getHeader().getKeyId();
        assertThat(newKid).isEqualTo(JwtService.keyId(newKey)).isNotEqualTo(kid);
        assertThatThrownBy(() -> service.parse(newToken)).isInstanceOf(JwtException.class);

        JwtService afterRotation = service(newKey, null);
        assertThat(afterRotation.parse(newToken).accountId()).isEqualTo(42L);
        assertThatThrownBy(() -> afterRotation.parse(oldToken)).isInstanceOf(JwtException.class);
    }

    @Test
    void tokenSignedWithAnUnrelatedKeyButAKnownKidIsRejected() {
        String forged = validBuilder().signWith(Keys.hmacShaKeyFor(randomKey(64)), Jwts.SIG.HS256).compact();

        assertThatThrownBy(() -> service.parse(forged)).isInstanceOf(SignatureException.class);
    }

    /** Test clock whose instant can be moved. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void set(Instant instant) {
            this.now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
