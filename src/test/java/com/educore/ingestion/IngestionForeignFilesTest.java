package com.educore.ingestion;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** Which directory entries the ingestion protocol considers its own; placeholders never are. */
class IngestionForeignFilesTest {

    private static final String UUID_TEXT = UUID.randomUUID().toString();

    @Test
    void placeholdersDotfilesAndDesktopMetadataAreForeign() {
        assertThat(IngestionDirectories.isForeign(".gitkeep")).isTrue();
        assertThat(IngestionDirectories.isForeign(".DS_Store")).isTrue();
        assertThat(IngestionDirectories.isForeign("Thumbs.db")).isTrue();
        assertThat(IngestionDirectories.isForeign("desktop.ini")).isTrue();
        assertThat(IngestionDirectories.isForeign(".gitkeep.report.json")).isTrue();
        assertThat(IngestionDirectories.isForeign(".courses.csv.part")).isTrue();
        assertThat(IngestionDirectories.isForeign("courses.csv")).isFalse();
    }

    @Test
    void onlySnapshotNamesWeCreateAreRecoverable() {
        assertThat(IngestionDirectories.isSnapshotName(IngestionDirectories.newSnapshotName("courses.csv"))).isTrue();
        assertThat(IngestionDirectories.isSnapshotName(IngestionDirectories.newSnapshotName(FileNames.FALLBACK)))
                .isTrue();
        assertThat(IngestionDirectories.isSnapshotName(".gitkeep")).isFalse();
        assertThat(IngestionDirectories.isSnapshotName("courses.csv")).isFalse();
        assertThat(IngestionDirectories.isSnapshotName(UUID_TEXT + "_courses.report.json")).isFalse();
        assertThat(IngestionDirectories.isSnapshotName(UUID_TEXT + "_.hidden.csv")).isFalse();
    }

    @Test
    void onlyStagedUploadNamesWeCreateAreRecoverable() {
        assertThat(IngestionDirectories.isStagedName(UUID_TEXT + "__courses.csv.staged")).isTrue();
        assertThat(IngestionDirectories.isStagedName("token__courses.csv.staged")).isFalse();
        assertThat(IngestionDirectories.isStagedName(".gitkeep")).isFalse();
        assertThat(IngestionDirectories.isStagedName(UUID_TEXT + "__courses.csv")).isFalse();
    }

    @Test
    void theInboxTakesOnlyVisibleCsvFiles() {
        assertThat(IngestionDirectories.isInboxCandidate("courses.csv")).isTrue();
        assertThat(IngestionDirectories.isInboxCandidate(".gitkeep")).isFalse();
        assertThat(IngestionDirectories.isInboxCandidate(".courses.csv")).isFalse();
        assertThat(IngestionDirectories.isInboxCandidate("notes.txt")).isFalse();
        assertThat(".gitkeep".matches(IngestionFlowConfig.INBOX_PATTERN)).isFalse();
        assertThat(".courses.csv".matches(IngestionFlowConfig.INBOX_PATTERN)).isFalse();
        assertThat("courses.csv".matches(IngestionFlowConfig.INBOX_PATTERN)).isTrue();
    }

    @Test
    void retentionDeletesOnlyItsOwnEntriesAndNeverPlaceholders() {
        assertThat(IngestionRetention.isOwnEntry(UUID_TEXT + "_courses.csv")).isTrue();
        assertThat(IngestionRetention.isOwnEntry("." + UUID_TEXT + "_courses." + UUID_TEXT + ".tmp")).isTrue();
        assertThat(IngestionRetention.isOwnEntry("." + UUID_TEXT + ".scrub.tmp")).isTrue();
        assertThat(IngestionRetention.isOwnEntry(".gitkeep")).isFalse();
        assertThat(IngestionRetention.isOwnEntry(".DS_Store")).isFalse();
        assertThat(IngestionRetention.isOwnEntry("Thumbs.db")).isFalse();
        assertThat(IngestionRetention.isOwnEntry(".csv")).isFalse();
        assertThat(IngestionRetention.isReport(UUID_TEXT + "_courses.report.json")).isTrue();
        assertThat(IngestionRetention.isReport(".gitkeep.report.json")).isFalse();
    }
}
