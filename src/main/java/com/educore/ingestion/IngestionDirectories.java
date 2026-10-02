package com.educore.ingestion;

import com.educore.config.EduCoreProperties;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The ingestion folder layout under {@code educore.ingestion.base-dir}:
 * <pre>
 * inbox/       watched; files are dropped here (and published by the upload endpoint after its commit)
 * staging/     uploads waiting for their transaction to commit (never watched)
 * processing/  &lt;uuid&gt;_&lt;name&gt;: the private snapshot an import reads
 * done/        SUCCEEDED and PARTIAL imports (PARTIAL with a &lt;name&gt;.report.json sidecar)
 * failed/      FAILED imports and rejected files, with a &lt;name&gt;.report.json sidecar
 * </pre>
 * A file is never imported from the inbox in place: only regular files that are not symbolic links (and,
 * where the file system reports it, have a single hard link) are accepted, and their bytes are copied into a
 * private snapshot in {@code processing/} with a size cap before the original is deleted. Validation, hashing
 * and the batch job read the snapshot only, so a writer that keeps the original open, a second hard link or a
 * swapped link cannot change what is imported. All folders must be on one file store (checked at startup);
 * every later transition is a single {@link StandardCopyOption#ATOMIC_MOVE}. Files are never renamed by
 * extension.
 * <p>
 * Only entries this protocol creates are ever acted on: {@code *.csv} in the inbox, {@code <uuid>_<safeName>}
 * snapshots in {@code processing/}, {@code <uuid>__<safeName>.staged} uploads in {@code staging/}. Dotfiles
 * ({@code .gitkeep}, {@code .DS_Store}, hidden temporary files of clients) and desktop metadata ({@code Thumbs.db})
 * are {@linkplain #isForeign foreign} and never imported, moved, reported or deleted.
 */
@Component
public class IngestionDirectories {

    static final String REPORT_SUFFIX = ".report.json";
    static final String STAGED_SUFFIX = ".staged";
    private static final String STAGED_SEPARATOR = "__";
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    /** A sanitised CSV name ({@link FileNames}): {@code [A-Za-z0-9._-]}, no leading dot, ends with {@code .csv}. */
    private static final String SAFE_NAME_PATTERN = "[A-Za-z0-9_-][A-Za-z0-9._-]*\\.csv";
    /** {@code <uuid>_<safeName>}: a snapshot written by {@link #newSnapshotName}. */
    private static final Pattern SNAPSHOT_NAME = Pattern.compile(UUID_PATTERN + "_" + SAFE_NAME_PATTERN);
    /** {@code <uuid>__<safeName>.staged}: an upload written by {@link #stage(String, byte[], String)}. */
    private static final Pattern STAGED_NAME = Pattern.compile("(" + UUID_PATTERN + ")" + STAGED_SEPARATOR
            + SAFE_NAME_PATTERN + Pattern.quote(STAGED_SUFFIX));
    /** Placeholders and desktop metadata that are never ours, whatever folder they are in. */
    private static final Set<String> FOREIGN_NAMES = Set.of("thumbs.db", "desktop.ini", "ehthumbs.db");

    private final Path base;
    private final Path inbox;
    private final Path staging;
    private final Path processing;
    private final Path done;
    private final Path failed;

    /** Result of {@link #snapshot}: the snapshot, or for an oversized original the original itself, moved. */
    public record Snapshot(Path file, boolean oversized) {
    }

    /** The inbox entry is a symbolic link, has several hard links, or is not a regular file. */
    public static class NotARegularFileException extends IOException {
        NotARegularFileException() {
            super("Inbox entry is not a single-link regular file");
        }
    }

    public IngestionDirectories(EduCoreProperties properties) {
        this.base = properties.ingestion().baseDir().toAbsolutePath().normalize();
        this.inbox = base.resolve("inbox");
        this.staging = base.resolve("staging");
        this.processing = base.resolve("processing");
        this.done = base.resolve("done");
        this.failed = base.resolve("failed");
        try {
            FileStore store = null;
            for (Path dir : List.of(inbox, staging, processing, done, failed)) {
                Files.createDirectories(dir);
                FileStore current = Files.getFileStore(dir);
                if (store != null && !store.equals(current)) {
                    throw new IllegalStateException("educore.ingestion.base-dir: inbox/, staging/, processing/, "
                            + "done/ and failed/ must be on one file system (atomic moves between them)");
                }
                store = current;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Ingestion folders could not be created under " + base, e);
        }
    }

    public Path base() {
        return base;
    }

    public Path inbox() {
        return inbox;
    }

    public Path staging() {
        return staging;
    }

    public Path processing() {
        return processing;
    }

    public Path done() {
        return done;
    }

    public Path failed() {
        return failed;
    }

    /** A new, unique snapshot name {@code <uuid>_<safeName>}. */
    static String newSnapshotName(String safeName) {
        return UUID.randomUUID() + "_" + safeName;
    }

    /**
     * Copies a regular inbox file into {@code processing/<snapshotName>} (at most {@code maxBytes} bytes) and
     * deletes the original. An original larger than {@code maxBytes} is not copied: it is moved (as is) to the
     * snapshot path and reported as oversized, so the operator keeps the file in {@code failed/}.
     *
     * @throws NotARegularFileException for a symbolic link, a multiply linked file or anything else
     * @throws IOException when the file cannot be read or deleted (the inbox is left as it was)
     */
    public Snapshot snapshot(Path original, String snapshotName, long maxBytes) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(original, BasicFileAttributes.class,
                LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || attributes.isSymbolicLink() || linkCount(original) > 1) {
            throw new NotARegularFileException();
        }
        Path target = processing.resolve(snapshotName);
        boolean oversized = false;
        try (InputStream in = Files.newInputStream(original, LinkOption.NOFOLLOW_LINKS);
             OutputStream out = Files.newOutputStream(target, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            byte[] buffer = new byte[64 * 1024];
            long copied = 0;
            int n;
            while ((n = in.read(buffer)) > 0) {
                if (copied + n > maxBytes) {
                    oversized = true;
                    break;
                }
                out.write(buffer, 0, n);
                copied += n;
            }
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        if (oversized) {
            Files.delete(target);
            return new Snapshot(Files.move(original, target, StandardCopyOption.ATOMIC_MOVE), true);
        }
        try {
            Files.delete(original);
        } catch (IOException e) {
            Files.deleteIfExists(target);
            throw e;
        }
        return new Snapshot(target, false);
    }

    /** Moves a rejected link or special entry out of the inbox into {@code failed/} without following it. */
    public Path quarantine(Path original, String name) throws IOException {
        return Files.move(original, failed.resolve(name), LinkOption.NOFOLLOW_LINKS, StandardCopyOption.ATOMIC_MOVE);
    }

    private static long linkCount(Path file) {
        try {
            Object count = Files.getAttribute(file, "unix:nlink", LinkOption.NOFOLLOW_LINKS);
            return count instanceof Number number ? number.longValue() : 1;
        } catch (UnsupportedOperationException | IllegalArgumentException | IOException e) {
            // The file system has no link count (e.g. NTFS through the Windows provider): the snapshot copy
            // still isolates the import from changes through other links.
            return 1;
        }
    }

    /** Moves a processing file to {@code done/} or {@code failed/}, keeping its name. */
    public Path finish(Path processingFile, boolean succeeded) throws IOException {
        Path target = (succeeded ? done : failed).resolve(processingFile.getFileName());
        return Files.move(processingFile, target, StandardCopyOption.ATOMIC_MOVE);
    }

    /** Writes {@code <name without .csv>.report.json} next to {@code file} (temporary file, then atomic move). */
    public Path writeReport(Path file, byte[] json) throws IOException {
        String name = file.getFileName().toString();
        String stem = name.endsWith(FileNames.EXTENSION) ? name.substring(0, name.length() - FileNames.EXTENSION.length())
                : name;
        Path report = file.resolveSibling(stem + REPORT_SUFFIX);
        Path temporary = file.resolveSibling("." + stem + "." + UUID.randomUUID() + ".tmp");
        Files.write(temporary, json);
        return Files.move(temporary, report, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    }

    /**
     * Writes an upload into {@code staging/<token>__<safeName>.staged} (not watched) and returns the token. The
     * file reaches the inbox only through {@link #publishStaged} after the upload's transaction committed.
     */
    public String stage(byte[] content, String safeName) throws IOException {
        String token = UUID.randomUUID().toString();
        stage(token, content, safeName);
        return token;
    }

    /** {@link #stage(byte[], String)} under a token chosen by the caller (registered before the file exists). */
    public void stage(String token, byte[] content, String safeName) throws IOException {
        Files.write(staging.resolve(token + STAGED_SEPARATOR + safeName + STAGED_SUFFIX), content,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    /** Last modification of a staged upload; empty when it is gone. */
    public Optional<Instant> stagedModifiedAt(String token) throws IOException {
        Optional<Path> staged = findStaged(token);
        if (staged.isEmpty()) {
            return Optional.empty();
        }
        try {
            return Optional.of(Files.getLastModifiedTime(staged.get(), LinkOption.NOFOLLOW_LINKS).toInstant());
        } catch (java.nio.file.NoSuchFileException e) {
            return Optional.empty();
        }
    }

    /** The inbox name a staged upload gets: {@code <first 8 characters of the token>_<safeName>}. */
    public static String inboxNameFor(String token, String safeName) {
        return token.substring(0, 8) + "_" + safeName;
    }

    /** Moves a staged upload into the inbox (atomic rename, same file store); empty when it is gone. */
    public Optional<Path> publishStaged(String token) throws IOException {
        Optional<Path> staged = findStaged(token);
        if (staged.isEmpty()) {
            return Optional.empty();
        }
        String safeName = safeNameOfStaged(staged.get());
        return Optional.of(Files.move(staged.get(), inbox.resolve(inboxNameFor(token, safeName)),
                StandardCopyOption.ATOMIC_MOVE));
    }

    /** Deletes a staged upload whose transaction did not commit. */
    public void discardStaged(String token) throws IOException {
        Optional<Path> staged = findStaged(token);
        if (staged.isPresent()) {
            Files.deleteIfExists(staged.get());
        }
    }

    /**
     * Tokens of uploads still in {@code staging/}: regular files named {@code <uuid>__<safeName>.staged} only;
     * anything else there ({@code .gitkeep}, desktop metadata, foreign files) is never touched.
     */
    public List<String> stagedTokens() throws IOException {
        List<String> tokens = new ArrayList<>();
        for (Path file : regularFiles(staging)) {
            Matcher matcher = STAGED_NAME.matcher(file.getFileName().toString());
            if (matcher.matches()) {
                tokens.add(matcher.group(1));
            }
        }
        return tokens;
    }

    private Optional<Path> findStaged(String token) throws IOException {
        String prefix = token + STAGED_SEPARATOR;
        return regularFiles(staging).stream()
                .filter(file -> isStagedName(file.getFileName().toString()))
                .filter(file -> file.getFileName().toString().startsWith(prefix)).findFirst();
    }

    /**
     * Whether {@code name} is never one of ours: a dotfile ({@code .gitkeep}, {@code .DS_Store}, editors' and
     * clients' temporary files) or desktop metadata such as {@code Thumbs.db}. Such entries are never imported,
     * moved, reported or deleted.
     */
    public static boolean isForeign(String name) {
        return name.isEmpty() || name.charAt(0) == '.' || FOREIGN_NAMES.contains(name.toLowerCase(Locale.ROOT));
    }

    /** Whether {@code name} is a snapshot name {@code <uuid>_<safeName>} written by {@link #newSnapshotName}. */
    public static boolean isSnapshotName(String name) {
        return SNAPSHOT_NAME.matcher(name).matches();
    }

    /** Whether {@code name} is a staged upload {@code <uuid>__<safeName>.staged}. */
    public static boolean isStagedName(String name) {
        return STAGED_NAME.matcher(name).matches();
    }

    /** Whether the inbox poller may take {@code name}: a {@code *.csv} that is not a dotfile. */
    public static boolean isInboxCandidate(String name) {
        return !isForeign(name) && name.endsWith(FileNames.EXTENSION);
    }

    private static String safeNameOfStaged(Path staged) {
        String name = staged.getFileName().toString();
        return name.substring(name.indexOf(STAGED_SEPARATOR) + STAGED_SEPARATOR.length(),
                name.length() - STAGED_SUFFIX.length());
    }

    /**
     * Snapshots in {@code processing/}: regular files (links not followed) named {@code <uuid>_<safeName>}. A
     * placeholder such as {@code .gitkeep} or any other foreign file is not an interrupted import and is left alone.
     */
    public List<Path> leftInProcessing() throws IOException {
        List<Path> snapshots = new ArrayList<>();
        for (Path file : regularFiles(processing)) {
            if (isSnapshotName(file.getFileName().toString())) {
                snapshots.add(file);
            }
        }
        return snapshots;
    }

    /**
     * Inbox entries the poller may take ({@link #isInboxCandidate}: {@code *.csv}, no dotfile), links included;
     * directories, placeholders and hidden temporary files are left out.
     */
    public List<Path> inInbox() throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(inbox)) {
            for (Path file : stream) {
                if (!Files.isDirectory(file, LinkOption.NOFOLLOW_LINKS)
                        && isInboxCandidate(file.getFileName().toString())) {
                    files.add(file);
                }
            }
        }
        return files;
    }

    /** Regular files (links not followed) of {@code dir} whose name is not {@link #isForeign foreign}. */
    private static List<Path> regularFiles(Path dir) throws IOException {
        List<Path> files = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(dir)) {
            for (Path file : stream) {
                if (!isForeign(file.getFileName().toString())
                        && Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
                    files.add(file);
                }
            }
        }
        return files;
    }
}
