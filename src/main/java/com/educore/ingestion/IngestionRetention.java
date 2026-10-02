package com.educore.ingestion;

import com.educore.config.EduCoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Erasure of CSV files kept on disk (AC-08, docs/ops/DATA_RETENTION.md).
 * <ul>
 *   <li>{@link #cleanup()} (cron {@code educore.ingestion.retention-cron}, hourly, UTC): deletes entries of
 *       {@code done/} older than {@code retain-processed-days} (0: all of them; with the default the import
 *       already deleted the file) and of {@code failed/} older than {@code retain-failed-days} (7): CSV files,
 *       {@code .report.json} sidecars, quarantined links and directories (never followed) and stale temporary
 *       report files; a sidecar only once its CSV is gone. Age is the entry's modification time. Placeholders
 *       ({@code .gitkeep}), desktop metadata ({@code .DS_Store}, {@code Thumbs.db}) and other foreign files are
 *       never deleted.</li>
 *   <li>{@link #scrub(String)}: called after an account purge with the purged student number; removes every
 *       line of {@code done/} and {@code failed/} files that carries it as a field (the rest of each file stays,
 *       byte for byte, with its modification time, so the retention clock is not reset). Course files are not
 *       touched. An original larger than {@code max-bytes} (rejected as {@code FILE_TOO_LARGE}, never imported) is
 *       deleted rather than scanned.</li>
 * </ul>
 * {@code processing/} (running imports), {@code inbox/} and {@code staging/} are never touched here.
 */
@Component
public class IngestionRetention {

    private static final Logger log = LoggerFactory.getLogger(IngestionRetention.class);
    private static final String COURSE_HEADER = CsvKind.COURSES.header();
    private static final char BOM = '﻿';
    /**
     * Hidden temporary files written here: {@code .<stem>.<uuid>.tmp} ({@link IngestionDirectories#writeReport})
     * and {@code .<uuid>.scrub.tmp} ({@link #scrub}).
     */
    private static final Pattern TEMPORARY = Pattern.compile(
            "\\.(?:.+\\.)?[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?:\\.scrub)?\\.tmp");

    /** What one {@link #cleanup()} deleted. */
    public record Cleanup(int done, int failed) {
    }

    /** What one {@link #scrub(String)} changed. */
    public record Scrub(int filesRewritten, int filesDeleted, int linesRemoved) {
    }

    private final IngestionDirectories directories;
    private final Clock clock;
    private final Duration retainProcessed;
    private final Duration retainFailed;
    private final long maxBytes;

    public IngestionRetention(IngestionDirectories directories, EduCoreProperties properties, Clock clock) {
        this.directories = directories;
        this.clock = clock;
        this.retainProcessed = Duration.ofDays(properties.ingestion().retainProcessedDays());
        this.retainFailed = Duration.ofDays(properties.ingestion().retainFailedDays());
        this.maxBytes = properties.ingestion().maxBytes().toBytes();
    }

    @Scheduled(cron = "${educore.ingestion.retention-cron:0 20 * * * *}", zone = "UTC")
    public void scheduledCleanup() {
        Cleanup result = cleanup();
        if (result.done() + result.failed() > 0) {
            log.info("Ingestion retention deleted done={} failed={}", result.done(), result.failed());
        }
    }

    /** Deletes processed and failed entries older than their retention period. */
    public synchronized Cleanup cleanup() {
        Instant now = clock.instant();
        return new Cleanup(deleteOlderThan(directories.done(), now.minus(retainProcessed)),
                deleteOlderThan(directories.failed(), now.minus(retainFailed)));
    }

    /**
     * Deletes the expired entries of {@code dir} that the ingestion wrote: {@code *.csv} entries (files, quarantined
     * links and directories) and our hidden temporary files first, then {@code .report.json} sidecars whose CSV is
     * gone (deleted in this pass or earlier), so a sidecar never outlives nor precedes its parent's deletion.
     * Placeholders ({@code .gitkeep}), desktop metadata and other foreign files are never deleted.
     */
    private int deleteOlderThan(Path dir, Instant cutoff) {
        int deleted = 0;
        List<Path> reports = new ArrayList<>();
        try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
            for (Path entry : entries) {
                String name = entry.getFileName().toString();
                if (isReport(name)) {
                    reports.add(entry);
                } else if (isOwnEntry(name)) {
                    deleted += deleteIfOlder(entry, cutoff);
                }
            }
        } catch (IOException e) {
            log.error("Ingestion retention could not clean a folder error={}", e.getClass().getName());
        }
        for (Path report : reports) {
            String name = report.getFileName().toString();
            Path parent = report.resolveSibling(name.substring(0, name.length() - IngestionDirectories.REPORT_SUFFIX
                    .length()) + FileNames.EXTENSION);
            if (!Files.exists(parent, LinkOption.NOFOLLOW_LINKS)) {
                deleted += deleteIfOlder(report, cutoff);
            }
        }
        return deleted;
    }

    private int deleteIfOlder(Path entry, Instant cutoff) {
        try {
            if (modifiedAt(entry).isBefore(cutoff)) {
                deleteEntry(entry);
                return 1;
            }
        } catch (NoSuchFileException gone) {
            // Removed concurrently.
        } catch (IOException e) {
            log.error("Ingestion retention could not delete an entry error={}", e.getClass().getName());
        }
        return 0;
    }

    /** A {@code .report.json} sidecar of a CSV (never a dotfile such as {@code .gitkeep.report.json}). */
    static boolean isReport(String name) {
        return !IngestionDirectories.isForeign(name) && name.endsWith(IngestionDirectories.REPORT_SUFFIX)
                && name.length() > IngestionDirectories.REPORT_SUFFIX.length();
    }

    /** A CSV entry written or quarantined by the ingestion, or one of its hidden temporary files. */
    static boolean isOwnEntry(String name) {
        if (IngestionDirectories.isForeign(name)) {
            return TEMPORARY.matcher(name).matches();
        }
        return name.endsWith(FileNames.EXTENSION) && name.length() > FileNames.EXTENSION.length();
    }

    /**
     * Removes the lines carrying {@code studentNumber} as a field from the files kept in {@code done/} and
     * {@code failed/}. Blank input does nothing.
     */
    public synchronized Scrub scrub(String studentNumber) {
        if (studentNumber == null || studentNumber.isBlank()) {
            return new Scrub(0, 0, 0);
        }
        Pattern field = Pattern.compile("(?:^|[,;\"'\\s])" + Pattern.quote(studentNumber.trim()) + "(?:$|[,;\"'\\s])");
        int rewritten = 0;
        int deleted = 0;
        int lines = 0;
        for (Path dir : List.of(directories.done(), directories.failed())) {
            try (DirectoryStream<Path> entries = Files.newDirectoryStream(dir)) {
                for (Path entry : entries) {
                    if (IngestionDirectories.isForeign(entry.getFileName().toString())) {
                        // Placeholders (.gitkeep), desktop metadata and temporary files are not kept imports.
                        continue;
                    }
                    try {
                        BasicFileAttributes attributes = Files.readAttributes(entry, BasicFileAttributes.class,
                                LinkOption.NOFOLLOW_LINKS);
                        if (!attributes.isRegularFile()) {
                            continue;
                        }
                        if (attributes.size() > maxBytes) {
                            Files.deleteIfExists(entry);
                            deleted++;
                            continue;
                        }
                        int removed = removeLines(entry, field, attributes.lastModifiedTime());
                        if (removed > 0) {
                            rewritten++;
                            lines += removed;
                        }
                    } catch (NoSuchFileException gone) {
                        // Removed concurrently.
                    }
                }
            } catch (IOException e) {
                log.error("Ingestion files could not be scrubbed after a purge error={}", e.getClass().getName());
            }
        }
        return new Scrub(rewritten, deleted, lines);
    }

    /** Rewrites {@code file} without the matching lines; ISO-8859-1 keeps every byte as it was. */
    private int removeLines(Path file, Pattern field, FileTime modified) throws IOException {
        String content = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
        String[] lines = content.split("(?<=\n)");
        if (lines.length > 0 && isCourseHeader(lines[0])) {
            return 0;
        }
        StringBuilder kept = new StringBuilder(content.length());
        int removed = 0;
        for (String line : lines) {
            if (field.matcher(line).find()) {
                removed++;
            } else {
                kept.append(line);
            }
        }
        if (removed == 0) {
            return 0;
        }
        Path temporary = file.resolveSibling("." + UUID.randomUUID() + ".scrub.tmp");
        try {
            Files.write(temporary, kept.toString().getBytes(StandardCharsets.ISO_8859_1));
            Files.setLastModifiedTime(temporary, modified);
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
        return removed;
    }

    private static boolean isCourseHeader(String firstLine) {
        String utf8 = new String(firstLine.getBytes(StandardCharsets.ISO_8859_1), StandardCharsets.UTF_8).strip();
        if (!utf8.isEmpty() && utf8.charAt(0) == BOM) {
            utf8 = utf8.substring(1);
        }
        return COURSE_HEADER.equals(utf8);
    }

    private static Instant modifiedAt(Path entry) throws IOException {
        return Files.getLastModifiedTime(entry, LinkOption.NOFOLLOW_LINKS).toInstant();
    }

    /** Deletes a file, a link (not its target) or a directory tree (links inside are not followed). */
    private static void deleteEntry(Path entry) throws IOException {
        if (!Files.isDirectory(entry, LinkOption.NOFOLLOW_LINKS)) {
            Files.deleteIfExists(entry);
            return;
        }
        Files.walkFileTree(entry, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException e) throws IOException {
                if (e != null) {
                    throw e;
                }
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
