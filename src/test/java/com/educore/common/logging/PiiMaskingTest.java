package com.educore.common.logging;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.json.JsonWriter;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every masking rule of {@link PiiMasking}, applied directly, through the Logback pattern converters (plain
 * format) and through the structured-logging customizer (JSON format).
 */
class PiiMaskingTest {

    /*
     * TEST DATA ONLY: values with the shape of real secrets, assembled at runtime from repetitive fragments so
     * no literal in the source looks like a credential.
     */
    private static final String JWT = base64Url("{\"alg\":\"none\"}") + "." + base64Url("{\"sub\":\"42\"}") + "."
            + "signature".repeat(3);
    private static final String REFRESH_TOKEN = "tok3n".repeat(9);
    private static final String TEMPORARY_PASSWORD = "Ab3#".repeat(6);
    private static final String HEX_DIGEST = "ab12".repeat(16);

    private static String base64Url(String json) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }

    @Nested
    class Rules {

        @Test
        void studentNumbersKeepTheirLastTwoDigits() {
            assertThat(PiiMasking.mask("imported studentNumber=20230145"))
                    .isEqualTo("imported studentNumber=******45");
            assertThat(PiiMasking.mask("{\"studentNumber\":\"4521\"}")).isEqualTo("{\"studentNumber\":\"**21\"}");
            assertThat(PiiMasking.mask("student no: 987654321012")).isEqualTo("student no: **********12");
            assertThat(PiiMasking.mask("row 7: duplicate 2023014578")).isEqualTo("row 7: duplicate ********78");
        }

        @ParameterizedTest
        @ValueSource(strings = {"listening on port 8080", "year 2026", "lockSeconds=900", "actor=42",
                "requestId=550e8400-e29b-41d4-a716-446655440000", "2026-10-01T10:15:30.123Z",
                "HHH000204: Processing", "AuthService.java:105"})
        void operationalNumbersAreKept(String text) {
            assertThat(PiiMasking.mask(text)).isEqualTo(text);
        }

        @ParameterizedTest
        @CsvSource({
                "contact ayse.yilmaz@example.com now, contact a***@example.com now",
                "<x.y+tag@mail.example.org>, <x***@mail.example.org>",
                "email=J@EXAMPLE.CO, email=J***@EXAMPLE.CO"})
        void emailsKeepTheFirstCharacterAndTheDomain(String text, String masked) {
            assertThat(PiiMasking.mask(text)).isEqualTo(masked);
        }

        @ParameterizedTest
        @CsvSource({
                "client 192.168.1.34 blocked, client 192.168.1.*** blocked",
                "ip=10.0.0.7, ip=10.0.0.***",
                "[127.0.0.1], [127.0.0.***]",
                "from 203.0.113.255:5432, from 203.0.113.***:5432"})
        void ipv4AddressesLoseTheirLastOctet(String text, String masked) {
            assertThat(PiiMasking.mask(text)).isEqualTo(masked);
        }

        @Test
        void versionNumbersAreNotTakenForAddresses() {
            assertThat(PiiMasking.mask("Spring Boot 3.5.16 on Java 21.0.4")).isEqualTo("Spring Boot 3.5.16 on Java 21.0.4");
        }

        @Test
        void bearerTokensAndJwtsAreRedacted() {
            assertThat(PiiMasking.mask("got Bearer " + REFRESH_TOKEN)).isEqualTo("got Bearer [REDACTED]");
            assertThat(PiiMasking.mask("header: bearer abc.def-ghi")).isEqualTo("header: bearer [REDACTED]");
            assertThat(PiiMasking.mask("token " + JWT + " expired")).isEqualTo("token [REDACTED-JWT] expired");
            assertThat(PiiMasking.mask("Authorization: Bearer " + JWT)).isEqualTo("Authorization: [REDACTED]");
        }

        @ParameterizedTest
        @CsvSource(delimiter = '|', quoteCharacter = '`', value = {
                "password=hunter2-but-longer|password=[REDACTED]",
                "{\"username\":\"ayse\",\"password\":\"plain text pw\"}|{\"username\":\"ayse\",\"password\":\"[REDACTED]\"}",
                "{\"currentPassword\":\"Old-pass-1\",\"newPassword\":\"New-pass-2\"}|{\"currentPassword\":\"[REDACTED]\",\"newPassword\":\"[REDACTED]\"}",
                "refreshToken: abc123|refreshToken: [REDACTED]",
                "Cookie: educore_refresh=abc; theme=dark|Cookie: [REDACTED]; theme=dark",
                "Set-Cookie=educore_refresh=x|Set-Cookie=[REDACTED]",
                "client_secret = s3cr3t|client_secret = [REDACTED]",
                "temporaryPassword='Zz9!'|temporaryPassword='[REDACTED]'",
                "api-key:k1|api-key:[REDACTED]"})
        void secretKeyValuePairsAreRedacted(String text, String masked) {
            assertThat(PiiMasking.mask(text)).isEqualTo(masked);
        }

        @Test
        void wordsThatOnlyMentionSecretsAreKept() {
            String text = "Password changed; revokedRefreshTokens 3; token rejected: ExpiredJwtException";
            assertThat(PiiMasking.mask(text)).isEqualTo(text);
        }

        @Test
        void generatedTemporaryPasswordsAreRedactedWhereverTheyAppear() {
            assertThat(PiiMasking.mask("created with " + TEMPORARY_PASSWORD))
                    .isEqualTo("created with [REDACTED]");
            assertThat(PiiMasking.mask("[" + TEMPORARY_PASSWORD + "]")).isEqualTo("[[REDACTED]]");
            assertThat(PiiMasking.mask("value \"" + TEMPORARY_PASSWORD + "\" set")).isEqualTo("value \"[REDACTED]\" set");
        }

        @ParameterizedTest
        @ValueSource(strings = {"eve_FORGEDREMOVED-DB-PASSWORD_level=INFO_", "Req-2026_Abc", "tooShort"})
        void mixedIdentifiersThatAreNotTemporaryPasswordsAreKept(String text) {
            String probe = "tooShort".equals(text) ? TEMPORARY_PASSWORD.substring(1) : text;
            assertThat(PiiMasking.mask(probe)).isEqualTo(probe);
            assertThat(PiiMasking.mask(TEMPORARY_PASSWORD + "x")).isEqualTo(TEMPORARY_PASSWORD + "x");
        }

        @Test
        void opaqueTokensAndHashesAreRedactedButUuidsAreKept() {
            assertThat(PiiMasking.mask("rotated " + REFRESH_TOKEN)).isEqualTo("rotated [REDACTED]");
            assertThat(PiiMasking.mask("hash " + HEX_DIGEST)).isEqualTo("hash [REDACTED]");
            String uuid = "requestId 3f2b8c1e-9d4a-4e7b-8f6a-1c2d3e4f5a6b";
            assertThat(PiiMasking.mask(uuid)).isEqualTo(uuid);
            String prefixed = "username=it-3f2b8c1e-9d4a-4e7b-8f6a-1c2d3e4f5a6b";
            assertThat(PiiMasking.mask(prefixed)).isEqualTo(prefixed);
        }

        @Test
        void classAndLoggerNamesAreKept() {
            String text = "com.educore.security.JwtAuthenticationFilter$$SpringCGLIB$$0 "
                    + "at org.springframework.web.filter.OncePerRequestFilter.doFilter(OncePerRequestFilter.java:116)";
            assertThat(PiiMasking.mask(text)).isEqualTo(text);
        }

        @Test
        void unicodeLineBreaksAndBidiControlsAreNeutralised() {
            assertThat(PiiMasking.mask("eve\u2028FORGED\u0085x\u2029y\u202ez")).isEqualTo("eve_FORGED_x_y_z");
            assertThat(PiiMasking.mask("multi\nline")).isEqualTo("multi\nline");
        }

        @Test
        void rejectedValuesOfBindingErrorsAreRedacted() {
            assertThat(PiiMasking.mask("Field error in object 'loginRequest' on field 'password': rejected value "
                    + "[my secret ]value]; codes [Size.loginRequest.password]"))
                    // The "'password': ..." pair rule also fires on the word after the field name; both redact.
                    .isEqualTo("Field error in object 'loginRequest' on field 'password': [REDACTED] value "
                            + "[[REDACTED]]; codes [Size.loginRequest.password]");
            assertThat(PiiMasking.mask("Field error in object 'r' on field 'username': rejected value [x]y]; codes [z]"))
                    .isEqualTo("Field error in object 'r' on field 'username': rejected value [[REDACTED]]; codes [z]");
            assertThat(PiiMasking.mask("rejected value [abc def]")).isEqualTo("rejected value [[REDACTED]]");
        }

        @Test
        void nullAndEmptyPassThrough() {
            assertThat(PiiMasking.mask(null)).isNull();
            assertThat(PiiMasking.mask("")).isEmpty();
        }
    }

    @Nested
    class PlainFormat {

        private final LoggerContext context = new LoggerContext();
        private final Logger logger = context.getLogger("com.educore.test");

        @Test
        void converterMasksTheFormattedMessageIncludingArguments() {
            LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.INFO,
                    "student {} from {} mailed {} with {}", null,
                    new Object[] {"studentNumber=20230145", "192.168.1.34", "ayse@example.com", "Bearer " + JWT});

            String rendered = new PiiMaskingConverter().convert(event);

            assertThat(rendered).isEqualTo(
                    "student studentNumber=******45 from 192.168.1.*** mailed a***@example.com with Bearer [REDACTED-JWT]");
        }

        @Test
        void throwableConverterMasksExceptionMessagesInTheStackTrace() {
            IllegalStateException failure = new IllegalStateException(
                    "duplicate key (email)=(ayse@example.com) from 10.1.2.3 password=" + TEMPORARY_PASSWORD);
            LoggingEvent event = new LoggingEvent(Logger.class.getName(), logger, Level.ERROR, "failed", failure,
                    null);
            PiiMaskingThrowableConverter converter = new PiiMaskingThrowableConverter();
            converter.setContext(context);
            converter.start();

            String rendered = converter.convert(event);

            assertThat(rendered).contains("IllegalStateException")
                    .contains("a***@example.com").contains("10.1.2.***").contains("password=[REDACTED]")
                    .doesNotContain("ayse@example.com").doesNotContain("10.1.2.3 ")
                    .doesNotContain(TEMPORARY_PASSWORD);
        }
    }

    @Nested
    class JsonFormat {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        void customizerMasksEveryStringMemberExceptTheCorrelationIds() throws Exception {
            Map<String, String> event = Map.of(
                    "message", "login from 192.168.1.34 by ayse@example.com with " + JWT,
                    "stack", "java.lang.IllegalStateException: studentNumber=20230145 token=" + REFRESH_TOKEN,
                    "requestId", "Req-2026_Abcdefgh-ijkl", "userId", "REMOVED-DB-PASSWORD567");
            JsonWriter<Map<String, String>> writer = JsonWriter.of(members -> {
                members.add("message", value -> value.get("message"));
                members.add("error").usingMembers(error -> error.add("stack_trace", value -> value.get("stack")));
                members.add("requestId", value -> value.get("requestId"));
                members.add("userId", value -> value.get("userId"));
                members.add("count", value -> 20230145);
                new PiiMaskingJsonCustomizer().customize(cast(members));
            });

            JsonNode json = mapper.readTree(writer.writeToString(event));

            assertThat(json.get("message").asText())
                    .isEqualTo("login from 192.168.1.*** by a***@example.com with [REDACTED-JWT]");
            assertThat(json.get("error").get("stack_trace").asText())
                    .isEqualTo("java.lang.IllegalStateException: studentNumber=******45 token=[REDACTED]");
            assertThat(json.get("requestId").asText()).isEqualTo("Req-2026_Abcdefgh-ijkl");
            assertThat(json.get("userId").asText()).isEqualTo("REMOVED-DB-PASSWORD567");
            assertThat(json.get("count").asInt()).isEqualTo(20230145);
        }

        @SuppressWarnings({"unchecked", "rawtypes"})
        private JsonWriter.Members<Object> cast(JsonWriter.Members<?> members) {
            return (JsonWriter.Members) members;
        }
    }
}
