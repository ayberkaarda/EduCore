package com.educore.lifecycle;

import com.educore.auth.UsernameHasher;
import com.educore.config.EduCoreProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The erasure ledger line format (AC-09): keyed digests only, domain-separated from the login username hash and
 * from audit pseudonyms, strictly parsed (a line that does not match exactly is never used), and the file is
 * append-only.
 */
class ErasureLedgerTest {

    /** TEST DATA ONLY: a pepper of the required length. */
    private static final String PEPPER = "erasure-ledger-test-only-pepper-0123456789";

    @TempDir
    Path dir;

    private static UsernameHasher hasher() {
        EduCoreProperties properties = mock(EduCoreProperties.class, RETURNS_DEEP_STUBS);
        when(properties.security().login().usernamePepper()).thenReturn(PEPPER);
        return new UsernameHasher(properties);
    }

    private static ErasureLedger ledger(Path file) {
        LifecycleProperties properties = new LifecycleProperties(30, "-", 500, "-",
                new LifecycleProperties.RetentionDays(90, 365), 1, 10000, file == null ? "" : file.toString());
        return new ErasureLedger(null, hasher(), properties);
    }

    @Test
    void digestsAreKeyedHexAndSeparatedFromLoginHashesAndPseudonyms() {
        ErasureLedger ledger = ledger(null);
        UsernameHasher hasher = hasher();

        assertThat(ledger.accountDigest(42)).matches("[0-9a-f]{64}").isEqualTo(ledger(null).accountDigest(42));
        assertThat(ledger.usernameDigest("20230017")).isNotEqualTo(ledger.studentNumberDigest("20230017"))
                .isNotEqualTo(hasher.hash("20230017"))
                .isNotEqualTo(hasher.hash("username:20230017"));
        assertThat(new Pseudonyms(hasher).ofAccount(42)).doesNotContain(ledger.accountDigest(42).substring(0, 16));
        assertThat(ledger.studentNumberDigest(null)).isNull();
        assertThat(ledger.studentNumberDigest(" ")).isNull();
    }

    @Test
    void linesRoundTripAndAnythingElseIsRejected() {
        String hex = "a".repeat(64);
        ErasureLedger.Entry entry = new ErasureLedger.Entry(hex, "b".repeat(64), null,
                Instant.parse("2026-10-02T03:30:00.123456Z"));

        assertThat(entry.line()).isEqualTo("v1 2026-10-02T03:30:00.123456Z " + hex + " " + "b".repeat(64) + " -");
        assertThat(ErasureLedger.Entry.parse(entry.line())).contains(entry);
        for (String bad : List.of("", "v2 2026-10-02T03:30:00Z " + hex + " " + hex + " -",
                "v1 2026-10-02T03:30:00Z " + hex.toUpperCase() + " " + hex + " -",
                "v1 2026-10-02T03:30:00Z " + hex + " " + hex,
                "v1 2026-10-02T03:30:00Z " + hex + " " + hex + " -'); DROP TABLE account; --",
                "v1 2026-13-45T03:30:00Z " + hex + " " + hex + " -")) {
            assertThat(ErasureLedger.Entry.parse(bad)).as(bad).isEmpty();
        }
    }

    @Test
    void theFileIsReadStrictlyAndOnlyMissingEntriesAreAppended() throws IOException {
        Path file = dir.resolve("ledger").resolve("erasure-ledger.log");
        ErasureLedger ledger = ledger(file);
        ErasureLedger.Entry first = new ErasureLedger.Entry("1".repeat(64), "2".repeat(64), "3".repeat(64),
                Instant.parse("2026-10-01T00:00:00Z"));
        ErasureLedger.Entry second = new ErasureLedger.Entry("4".repeat(64), "5".repeat(64), null,
                Instant.parse("2026-10-02T00:00:00Z"));

        assertThat(ledger.fileEntries()).isEmpty();
        assertThat(ledger.appendMissingToFile(List.of(first))).isOne();
        Files.writeString(file, "garbage line\n", StandardCharsets.US_ASCII, java.nio.file.StandardOpenOption.APPEND);
        assertThat(ledger.appendMissingToFile(List.of(first, second))).isOne();

        assertThat(ledger.fileEntries()).containsExactly(first, second);
        assertThat(Files.readAllLines(file, StandardCharsets.US_ASCII)).hasSize(3).startsWith(first.line());
    }
}
