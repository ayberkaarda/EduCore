package com.educore.publicapi;

import com.educore.course.CourseSlugs;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/** Slug derivation used when an ADMIN creates or updates a course without an explicit slug. */
class CourseSlugsTest {

    @Test
    void stemsAreLowercaseAsciiHyphenGroups() {
        assertThat(CourseSlugs.stem("Advanced Web Development")).isEqualTo("advanced-web-development");
        assertThat(CourseSlugs.stem("  Systems Programming (Rust)  ")).isEqualTo("systems-programming-rust");
        assertThat(CourseSlugs.stem("İleri Düzey Ağ Güvenliği")).isEqualTo("ileri-duzey-ag-guvenligi");
        assertThat(CourseSlugs.stem("Işık ve Öğrenme")).isEqualTo("isik-ve-ogrenme");
        assertThat(CourseSlugs.stem("Café Économie")).isEqualTo("cafe-economie");
        assertThat(CourseSlugs.stem("C++ / C#")).isEqualTo("c-c");
        assertThat(CourseSlugs.stem("<script>alert(1)</script>")).isEqualTo("script-alert-1-script");
        assertThat(CourseSlugs.stem("!!!")).isEqualTo("course");
        assertThat(CourseSlugs.stem("x".repeat(49) + " tail")).isEqualTo("x".repeat(49));
        assertThat(CourseSlugs.stem("word ".repeat(40))).hasSizeLessThanOrEqualTo(50).matches(CourseSlugs.PATTERN);
    }

    @Test
    void uniqueAppendsTheFirstFreeCounter() {
        Set<String> taken = Set.of("data", "data-2", "data-3");

        assertThat(CourseSlugs.unique("Data", taken::contains)).isEqualTo("data-4");
        assertThat(CourseSlugs.unique("Fresh Name", taken::contains)).isEqualTo("fresh-name");
        assertThat(CourseSlugs.unique("Data", Set.of("data")::contains)).isEqualTo("data-2");
    }
}
