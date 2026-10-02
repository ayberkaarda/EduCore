package com.educore.ingestion;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class FileNamesAndMaskingTest {

    @Test
    void keepsOnlySafeCharactersAndDropsDirectories() {
        assertThat(FileNames.sanitize("students 2026 (final).csv")).hasValue("students_2026__final_.csv");
        assertThat(FileNames.sanitize("../../etc/passwd.csv")).hasValue("passwd.csv");
        assertThat(FileNames.sanitize("..\\..\\windows\\win.ini.csv")).hasValue("win.ini.csv");
        assertThat(FileNames.sanitize("...hidden.csv")).hasValue("hidden.csv");
        assertThat(FileNames.sanitize("a..b.csv")).hasValue("a.b.csv");
        assertThat(FileNames.sanitize("Öğrenci.CSV")).hasValue("__renci.csv");
    }

    @Test
    void rejectsNamesWithoutACsvExtensionOrWithoutUsableCharacters() {
        assertThat(FileNames.sanitize("students.xlsx")).isEmpty();
        assertThat(FileNames.sanitize("students.csv.exe")).isEmpty();
        assertThat(FileNames.sanitize(".csv")).isEmpty();
        assertThat(FileNames.sanitize("..csv")).isEmpty();
        assertThat(FileNames.sanitize("çşğ.csv")).isEmpty();
        assertThat(FileNames.sanitize(null)).isEmpty();
    }

    @Test
    void limitsTheLength() {
        assertThat(FileNames.sanitize("a".repeat(300) + ".csv")).hasValueSatisfying(name -> {
            assertThat(name).hasSize(FileNames.MAX_LENGTH);
            assertThat(name).endsWith(".csv");
        });
    }

    @Test
    void masksEveryWordAfterItsFirstCharacter() {
        assertThat(PiiMasker.mask("Ayşe,Yılmaz,20230017")).isEqualTo("A***,Y***,2***");
        assertThat(PiiMasker.mask("\"O'Neil, Mary\",X,9")).isEqualTo("\"O'N***, M***\",X,9");
        assertThat(PiiMasker.mask("line\nbreak")).isEqualTo("l*** b***");
        assertThat(PiiMasker.mask(null)).isNull();
        assertThat(PiiMasker.mask("a,".repeat(400))).hasSize(PiiMasker.MAX_LENGTH);
    }
}
