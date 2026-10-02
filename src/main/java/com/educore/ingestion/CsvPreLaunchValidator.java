package com.educore.ingestion;

import com.educore.config.EduCoreProperties;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Checks a CSV file before any job starts, in one streaming pass that stops at the first violation:
 * <ul>
 *   <li>size: {@code 0 < bytes <= max-bytes}, counted while reading (a file that grows cannot pass);</li>
 *   <li>strict UTF-8 (a leading BOM is tolerated); text only: no NUL or other control character except tab,
 *       CR and LF;</li>
 *   <li>line breaks: LF or CR LF; a bare CR is rejected (the batch reader would treat it as a line end, so
 *       validation and import use the same record boundaries);</li>
 *   <li>every physical line at most {@code max-record-length} characters;</li>
 *   <li>header line equal to a {@link CsvKind} header; {@code 1 <= non-blank data lines <= max-rows} (a
 *       quoted field spanning lines counts once per physical line, so the limit errs on the safe side).</li>
 * </ul>
 * The SHA-256 of the raw bytes is computed in the same pass. The pipeline validates its private snapshot
 * of the file, never the inbox original.
 */
@Component
public class CsvPreLaunchValidator {

    private final long maxBytes;
    private final int maxRows;
    private final int maxRecordLength;

    public CsvPreLaunchValidator(EduCoreProperties properties) {
        this.maxBytes = properties.ingestion().maxBytes().toBytes();
        this.maxRows = properties.ingestion().maxRows();
        this.maxRecordLength = properties.ingestion().maxRecordLength();
    }

    public long maxBytes() {
        return maxBytes;
    }

    /** Validates a file (the pipeline's private snapshot). */
    public ValidatedCsv validate(Path file) {
        try (InputStream in = Files.newInputStream(file)) {
            return validate(in);
        } catch (IOException e) {
            throw new UncheckedIOException("CSV file could not be read", e);
        }
    }

    /** Validates complete content held in memory. */
    public ValidatedCsv validate(byte[] bytes) {
        try {
            return validate(new ByteArrayInputStream(bytes));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Validates a stream; reads at most {@code max-bytes + 1} bytes. */
    public ValidatedCsv validate(InputStream raw) throws IOException {
        MessageDigest sha256 = sha256Digest();
        BoundedInputStream bounded = new BoundedInputStream(raw, maxBytes);
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        BufferedReader reader = new BufferedReader(new InputStreamReader(new DigestInputStream(bounded, sha256),
                decoder), 8192);
        StringBuilder header = new StringBuilder();
        boolean inHeader = true;
        boolean first = true;
        boolean pendingCr = false;
        boolean blankLine = true;
        int lineLength = 0;
        int rows = 0;
        int c;
        try {
            while ((c = reader.read()) != -1) {
                if (first) {
                    first = false;
                    if (c == '﻿') {
                        continue;
                    }
                }
                if (pendingCr && c != '\n') {
                    throw new IngestionRejectedException(IngestionReason.INVALID_LINE_BREAK);
                }
                if (c == '\r') {
                    pendingCr = true;
                    continue;
                }
                if (c == '\n') {
                    pendingCr = false;
                    if (inHeader) {
                        checkHeader(header);
                        inHeader = false;
                    } else if (!blankLine && ++rows > maxRows) {
                        throw new IngestionRejectedException(IngestionReason.TOO_MANY_ROWS);
                    }
                    blankLine = true;
                    lineLength = 0;
                    continue;
                }
                if (c != '\t' && Character.isISOControl(c)) {
                    throw new IngestionRejectedException(IngestionReason.NOT_TEXT);
                }
                if (++lineLength > maxRecordLength) {
                    throw new IngestionRejectedException(IngestionReason.RECORD_TOO_LONG);
                }
                if (inHeader) {
                    header.append((char) c);
                } else if (blankLine && !Character.isWhitespace(c)) {
                    blankLine = false;
                }
            }
        } catch (CharacterCodingException e) {
            throw new IngestionRejectedException(IngestionReason.NOT_UTF8);
        }
        if (bounded.count() == 0) {
            throw new IngestionRejectedException(IngestionReason.EMPTY_FILE);
        }
        if (pendingCr) {
            throw new IngestionRejectedException(IngestionReason.INVALID_LINE_BREAK);
        }
        if (inHeader) {
            checkHeader(header);
        } else if (!blankLine && ++rows > maxRows) {
            throw new IngestionRejectedException(IngestionReason.TOO_MANY_ROWS);
        }
        if (rows == 0) {
            throw new IngestionRejectedException(IngestionReason.NO_DATA_ROWS);
        }
        CsvKind kind = CsvKind.forHeader(header.toString()).orElseThrow();
        return new ValidatedCsv(kind, HexFormat.of().formatHex(sha256.digest()), bounded.count(), rows);
    }

    private static void checkHeader(CharSequence header) {
        if (CsvKind.forHeader(header.toString()).isEmpty()) {
            throw new IngestionRejectedException(IngestionReason.INVALID_HEADER);
        }
    }

    private static MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }

    static String sha256(byte[] bytes) {
        return HexFormat.of().formatHex(sha256Digest().digest(bytes));
    }

    /** Counts bytes and rejects the stream as {@code FILE_TOO_LARGE} once more than {@code limit} were read. */
    private static final class BoundedInputStream extends FilterInputStream {

        private final long limit;
        private long count;

        BoundedInputStream(InputStream in, long limit) {
            super(in);
            this.limit = limit;
        }

        long count() {
            return count;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b != -1) {
                add(1);
            }
            return b;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            int n = super.read(buffer, offset, (int) Math.min(length, limit + 1 - count));
            if (n > 0) {
                add(n);
            }
            return n;
        }

        private void add(long n) {
            count += n;
            if (count > limit) {
                throw new IngestionRejectedException(IngestionReason.FILE_TOO_LARGE);
            }
        }
    }
}
