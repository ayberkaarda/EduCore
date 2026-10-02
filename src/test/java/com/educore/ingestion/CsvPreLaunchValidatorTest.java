package com.educore.ingestion;

import com.educore.config.EduCoreProperties;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CsvPreLaunchValidatorTest {

    private final CsvPreLaunchValidator validator = new CsvPreLaunchValidator(properties(Map.of(
            "educore.ingestion.max-bytes", "200B",
            "educore.ingestion.max-rows", "3")));

    static EduCoreProperties properties(Map<String, String> values) {
        return new Binder(new MapConfigurationPropertySource(values)).bindOrCreate("educore", EduCoreProperties.class);
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void acceptsAStudentFileAndDetectsItsKindFromTheHeader() {
        byte[] content = utf8("FirstName,LastName,StudentNumber\r\nAyşe,Yılmaz,97000001\r\n\r\nAli,Kaya,97000002\r\n");

        ValidatedCsv csv = validator.validate(content);

        assertThat(csv.kind()).isEqualTo(CsvKind.STUDENTS);
        assertThat(csv.rows()).isEqualTo(2);
        assertThat(csv.size()).isEqualTo(content.length);
        assertThat(csv.sha256()).hasSize(64).isEqualTo(CsvPreLaunchValidator.sha256(content));
    }

    @Test
    void toleratesAUtf8ByteOrderMark() {
        byte[] body = utf8("name,term,instructor\nCourse,2026/1,Instructor\n");
        byte[] withBom = new byte[body.length + 3];
        withBom[0] = (byte) 0xEF;
        withBom[1] = (byte) 0xBB;
        withBom[2] = (byte) 0xBF;
        System.arraycopy(body, 0, withBom, 3, body.length);

        assertThat(validator.validate(withBom).kind()).isEqualTo(CsvKind.COURSES);
    }

    @Test
    void rejectsAHeaderThatDiffersInCaseOrOrder() {
        assertReason("firstname,lastname,studentnumber\nA,B,9700\n", IngestionReason.INVALID_HEADER);
        assertReason("LastName,FirstName,StudentNumber\nA,B,9700\n", IngestionReason.INVALID_HEADER);
        assertReason("FirstName;LastName;StudentNumber\nA;B;9700\n", IngestionReason.INVALID_HEADER);
    }

    @Test
    void rejectsEmptyOversizedAndHeaderOnlyFiles() {
        assertThatThrownBy(() -> validator.validate(new byte[0]))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(IngestionReason.EMPTY_FILE));
        assertReason("name,term,instructor\n" + "x".repeat(200) + "\n", IngestionReason.FILE_TOO_LARGE);
        assertReason("name,term,instructor\n\n", IngestionReason.NO_DATA_ROWS);
        assertReason("name,term,instructor\na\nb\nc\nd\n", IngestionReason.TOO_MANY_ROWS);
    }

    @Test
    void rejectsInvalidUtf8() {
        byte[] latin1 = "name,term,instructor\nÇalışma,2026/1,Öğretmen\n".getBytes(StandardCharsets.ISO_8859_1);

        assertThatThrownBy(() -> validator.validate(latin1))
                .isInstanceOfSatisfying(IngestionRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(IngestionReason.NOT_UTF8);
                    assertThat(e.code()).isEqualTo("import/not-utf8");
                    assertThat(e.status().value()).isEqualTo(400);
                });
    }

    @Test
    void rejectsBinaryAndControlCharacters() {
        assertReason("name,term,instructor\nA\u0000B,1,2\n", IngestionReason.NOT_TEXT);
        assertReason("name,term,instructor\nA\u0007B,1,2\n", IngestionReason.NOT_TEXT);
        // Tab, CR and LF are text.
        assertThat(validator.validate(utf8("name,term,instructor\nA\tB,1,2\r\n")).rows()).isEqualTo(1);
    }

    @Test
    void lineBreaksAreLfOrCrLfAndLinesAreBounded() {
        assertReason("name,term,instructor\nA,1,2\rB,1,2\n", IngestionReason.INVALID_LINE_BREAK);
        assertReason("name,term,instructor\rA,1,2\n", IngestionReason.INVALID_LINE_BREAK);
        assertReason("name,term,instructor\nA,1,2\r", IngestionReason.INVALID_LINE_BREAK);
        assertThat(validator.validate(utf8("name,term,instructor\r\nA,1,2\r\nB,1,2")).rows()).isEqualTo(2);
        CsvPreLaunchValidator shortLines = new CsvPreLaunchValidator(properties(Map.of(
                "educore.ingestion.max-record-length", "25")));
        assertThatThrownBy(() -> shortLines.validate(utf8("name,term,instructor\n" + "x".repeat(26) + "\n")))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(IngestionReason.RECORD_TOO_LONG));
    }

    @Test
    void stopsReadingAtTheByteLimitEvenWhenTheStreamKeepsGrowing() {
        java.io.InputStream endless = new java.io.InputStream() {
            private final byte[] header = utf8("name,term,instructor\n");
            private long position;

            @Override
            public int read() {
                return position < header.length ? header[(int) position++] : 'x';
            }
        };

        assertThatThrownBy(() -> validator.validate(endless))
                .isInstanceOfSatisfying(IngestionRejectedException.class,
                        e -> assertThat(e.reason()).isEqualTo(IngestionReason.FILE_TOO_LARGE));
    }

    @Test
    void fileTooLargeIsA413Problem() {
        assertThatThrownBy(() -> validator.validate(utf8("x".repeat(201))))
                .isInstanceOfSatisfying(IngestionRejectedException.class, e -> {
                    assertThat(e.code()).isEqualTo("import/file-too-large");
                    assertThat(e.status().value()).isEqualTo(413);
                });
    }

    private void assertReason(String content, IngestionReason reason) {
        assertThatThrownBy(() -> validator.validate(utf8(content)))
                .isInstanceOfSatisfying(IngestionRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
    }
}
