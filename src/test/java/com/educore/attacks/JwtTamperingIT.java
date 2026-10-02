package com.educore.attacks;

import com.educore.authz.AuthzIntegrationSupport;
import com.educore.entity.Account;
import com.educore.entity.Role;
import com.fasterxml.jackson.databind.JsonNode;
import io.jsonwebtoken.JwtBuilder;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.MvcResult;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * Attacker holding the token format but trying to forge or tamper an access token.
 *
 * <p>Attack: craft access tokens with {@code alg=none}; an HS384/HS512 "algorithm confusion" signature; a
 * {@code kid} that is unknown, or carries path-traversal / SQL-injection text; an expired {@code exp}; a future
 * {@code nbf}; a wrong {@code iss} or {@code aud}; no {@code exp}; an oversized token; a signature made with a
 * key the server does not hold (a retired rotation key); and valid tokens for an account that was soft-deleted
 * or is pending deletion. The forged tokens are signed with the real {@code test}-profile signing secret where
 * a valid signature is wanted, so only the specific defect is under test.
 *
 * <p>Expected defence: {@code JwtService} accepts only HS256, requires a known {@code kid}, the exact issuer
 * and audience, a present and unexpired {@code exp}, and a numeric subject, all within 30&nbsp;s of skew; every
 * rejection is caught in {@code JwtAuthenticationFilter} and answered 401 {@code auth/unauthenticated} (never a
 * 500), and the live DB account state (not the token's {@code roles} claim) decides authority, so a
 * deactivated account's token is anonymous and a pending-deletion token is confined to the restore-only scope.
 */
@Tag("attack")
class JwtTamperingIT extends AuthzIntegrationSupport {

    /** The actual signing bytes of the {@code test} profile (application-test.yml), base64-decoded. */
    private static final byte[] KEY = Base64.getDecoder()
            .decode("ZWR1Y29yZS10ZXN0LW9ubHktand0LXNpZ25pbmctc2VjcmV0LW5ldmVyLXVzZS1vdXRzaWRlLXRlc3Rz");
    private static final String ISSUER = "educore";
    private static final String AUDIENCE = "educore-api";

    /** Replicates JwtService.keyId: hex of the first 8 bytes of SHA-256("educore-jwt-kid:" + keyBytes). */
    private static String kid(byte[] key) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        md.update("educore-jwt-kid:".getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(Arrays.copyOf(md.digest(key), 8));
    }

    /** A builder pre-filled with a currently valid claim set for {@code accountId}; mutate, then compact. */
    private JwtBuilder valid(long accountId) throws Exception {
        Instant now = Instant.now();
        return Jwts.builder()
                .header().keyId(kid(KEY)).and()
                .issuer(ISSUER)
                .audience().add(AUDIENCE).and()
                .subject(Long.toString(accountId))
                .id(UUID.randomUUID().toString())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(15, ChronoUnit.MINUTES)))
                .claim("roles", List.of("USER"));
    }

    private MvcResult callMeWith(String rawToken) throws Exception {
        return mockMvc.perform(get("/api/v1/me").with(from(newIp()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + rawToken)).andReturn();
    }

    private void assertUnauthenticated(String rawToken, String description) throws Exception {
        MvcResult result = callMeWith(rawToken);
        assertThat(result.getResponse().getStatus()).as(description).isEqualTo(401);
        assertThat(result.getResponse().getContentType()).as(description).startsWith("application/problem+json");
        assertThat(body(result).get("code").asText()).as(description).isEqualTo("auth/unauthenticated");
    }

    @Test
    void aValidlySignedTokenIsTheControlAndIsAccepted() throws Exception {
        Account user = account(Role.USER);
        String token = valid(user.getId()).signWith(new SecretKeySpec(KEY, "HmacSHA256"), Jwts.SIG.HS256).compact();

        assertThat(callMeWith(token).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    void algNoneUnsignedTokenIsRejected() throws Exception {
        Account user = account(Role.USER);
        String token = valid(user.getId()).compact(); // no signWith -> unsecured JWS, alg "none"

        assertUnauthenticated(token, "alg=none");
    }

    @Test
    void algorithmConfusionHs384AndHs512AreRejected() throws Exception {
        Account user = account(Role.USER);
        String hs384 = valid(user.getId()).signWith(new SecretKeySpec(KEY, "HmacSHA384"), Jwts.SIG.HS384).compact();
        byte[] hs512Key = new byte[64];
        for (int i = 0; i < hs512Key.length; i++) {
            hs512Key[i] = KEY[i % KEY.length];
        }
        String hs512 = valid(user.getId()).signWith(new SecretKeySpec(hs512Key, "HmacSHA512"), Jwts.SIG.HS512).compact();

        assertUnauthenticated(hs384, "HS384 confusion");
        assertUnauthenticated(hs512, "HS512 confusion");
    }

    @Test
    void unknownKidAndKidInjectionPayloadsAreRejectedWithoutError() throws Exception {
        Account user = account(Role.USER);
        SecretKeySpec key = new SecretKeySpec(KEY, "HmacSHA256");
        for (String maliciousKid : List.of("../../../../etc/passwd", "..\\..\\windows\\win.ini",
                "' OR '1'='1", "\"; DROP TABLE refresh_token;--", "deadbeefdeadbeef", "")) {
            String token = Jwts.builder().header().keyId(maliciousKid).and()
                    .issuer(ISSUER).audience().add(AUDIENCE).and().subject(Long.toString(user.getId()))
                    .id(UUID.randomUUID().toString()).issuedAt(new Date())
                    .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                    .signWith(key, Jwts.SIG.HS256).compact();
            assertUnauthenticated(token, "kid=" + maliciousKid);
        }
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        Account user = account(Role.USER);
        Instant past = Instant.now().minus(1, ChronoUnit.HOURS);
        String token = valid(user.getId())
                .issuedAt(Date.from(past))
                .expiration(Date.from(past.plus(1, ChronoUnit.MINUTES)))
                .signWith(new SecretKeySpec(KEY, "HmacSHA256"), Jwts.SIG.HS256).compact();

        assertUnauthenticated(token, "expired exp");
    }

    @Test
    void futureNotBeforeIsRejected() throws Exception {
        Account user = account(Role.USER);
        String token = valid(user.getId())
                .notBefore(Date.from(Instant.now().plus(1, ChronoUnit.HOURS)))
                .signWith(new SecretKeySpec(KEY, "HmacSHA256"), Jwts.SIG.HS256).compact();

        assertUnauthenticated(token, "future nbf");
    }

    @Test
    void wrongIssuerAndWrongAudienceAreRejected() throws Exception {
        Account user = account(Role.USER);
        SecretKeySpec key = new SecretKeySpec(KEY, "HmacSHA256");
        String wrongIssuer = Jwts.builder().header().keyId(kid(KEY)).and()
                .issuer("attacker").audience().add(AUDIENCE).and().subject(Long.toString(user.getId()))
                .id(UUID.randomUUID().toString()).issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(key, Jwts.SIG.HS256).compact();
        String wrongAudience = Jwts.builder().header().keyId(kid(KEY)).and()
                .issuer(ISSUER).audience().add("some-other-api").and().subject(Long.toString(user.getId()))
                .id(UUID.randomUUID().toString()).issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(key, Jwts.SIG.HS256).compact();

        assertUnauthenticated(wrongIssuer, "wrong iss");
        assertUnauthenticated(wrongAudience, "wrong aud");
    }

    @Test
    void tokenWithoutExpirationIsRejected() throws Exception {
        Account user = account(Role.USER);
        String token = Jwts.builder().header().keyId(kid(KEY)).and()
                .issuer(ISSUER).audience().add(AUDIENCE).and().subject(Long.toString(user.getId()))
                .id(UUID.randomUUID().toString()).issuedAt(new Date())
                .signWith(new SecretKeySpec(KEY, "HmacSHA256"), Jwts.SIG.HS256).compact();

        assertUnauthenticated(token, "missing exp");
    }

    @Test
    void oversizedTokenIsRejectedBeforeAnyCryptography() throws Exception {
        Account user = account(Role.USER);
        String token = valid(user.getId())
                .claim("padding", "A".repeat(8192))
                .signWith(new SecretKeySpec(KEY, "HmacSHA256"), Jwts.SIG.HS256).compact();
        assertThat(token.length()).isGreaterThan(4096);

        assertUnauthenticated(token, "oversized token");
    }

    @Test
    void tokenSignedWithARetiredOrUnknownKeyIsRejected() throws Exception {
        Account user = account(Role.USER);
        byte[] foreignKey = new byte[64];
        Arrays.fill(foreignKey, (byte) 0x5a);
        // Correct kid of the foreign key, so the token is internally consistent but the server never held it.
        String token = Jwts.builder().header().keyId(kid(foreignKey)).and()
                .issuer(ISSUER).audience().add(AUDIENCE).and().subject(Long.toString(user.getId()))
                .id(UUID.randomUUID().toString()).issuedAt(new Date())
                .expiration(Date.from(Instant.now().plus(15, ChronoUnit.MINUTES)))
                .signWith(new SecretKeySpec(foreignKey, "HmacSHA256"), Jwts.SIG.HS256).compact();

        assertUnauthenticated(token, "token signed with a key the server does not hold");
    }

    @Test
    void validSignatureWithTamperedSubjectForANonExistentAccountIsAnonymous() throws Exception {
        String token = valid(Long.MAX_VALUE)
                .signWith(new SecretKeySpec(KEY, "HmacSHA256"), Jwts.SIG.HS256).compact();

        assertUnauthenticated(token, "valid signature, unknown account id");
    }

    @Test
    void aValidTokenForADeactivatedAccountIsTreatedAsAnonymous() throws Exception {
        Account user = account(Role.USER);
        String token = bearer(user).substring("Bearer ".length());
        jdbc.update("UPDATE account SET status = 'DEACTIVATED', deleted_at = now() WHERE id = ?", user.getId());

        assertUnauthenticated(token, "deactivated account token");
    }

    @Test
    void aValidTokenForAPendingDeletionAccountIsConfinedToTheRestoreScope() throws Exception {
        Account user = account(Role.USER);
        String token = bearer(user).substring("Bearer ".length());
        jdbc.update("UPDATE account SET status = 'PENDING_DELETION', deleted_at = now(), "
                + "delete_after = now() + interval '30 days' WHERE id = ?", user.getId());

        // /api/v1/me is inside the restore-only scope, so it still answers.
        assertThat(callMeWith(token).getResponse().getStatus()).isEqualTo(200);
        // Any other route is refused by the pending-deletion scope filter, not granted.
        MvcResult blocked = mockMvc.perform(get("/api/v1/courses").with(from(newIp()))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)).andReturn();
        assertThat(blocked.getResponse().getStatus()).isEqualTo(403);
        JsonNode problem = body(blocked);
        assertThat(problem.get("code").asText()).isEqualTo("account/pending-deletion");
    }
}
