package dev.ioexception.dicom.common;

import dev.ioexception.dicom.dto.ValidatedDicomPayload;
import dev.ioexception.dicom.dto.response.DicomUidResponse;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.mime.MultipartInputStream;
import org.dcm4che3.mime.MultipartParser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

@Slf4j
public final class DicomMultipartParserUtil {

    private static final int MAX_BOUNDARY_LINE_BYTES = 512;
    private static final int MAX_PREAMBLE_BYTES = 8 * 1024;
    private static final int MAX_MIME_HEADER_BYTES = 64 * 1024;

    private DicomMultipartParserUtil() {
    }

    public static String extractBoundary(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }

        String[] tokens = contentType.split(";");
        for (String token : tokens) {
            token = token.trim();
            if (token.toLowerCase(Locale.ROOT).startsWith("boundary=")) {
                String boundary = token.substring(9).trim();
                if (boundary.startsWith("\"") && boundary.endsWith("\"") && boundary.length() >= 2) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                if (boundary.startsWith("'") && boundary.endsWith("'") && boundary.length() >= 2) {
                    boundary = boundary.substring(1, boundary.length() - 1);
                }
                return boundary;
            }
        }

        log.warn("[DicomMultipartParserUtil] Content-Type 헤더에서 boundary를 찾을 수 없습니다: {}", contentType);
        return null;
    }

    /**
     * Validates every DICOM part in a repeatable, file-backed STOW-RS payload.
     * The spool file is opened read-only and is never copied into heap memory.
     */
    public static ValidatedDicomPayload validateSingleStudy(
            Path spoolFile,
            String headerBoundary,
            long maxMetadataBytes) throws IOException {
        Objects.requireNonNull(spoolFile, "spoolFile");
        if (!Files.isRegularFile(spoolFile)) {
            throw new IOException("DICOM spool file does not exist or is not a regular file: " + spoolFile);
        }
        if (maxMetadataBytes <= 0) {
            throw new IllegalArgumentException("maxMetadataBytes must be greater than zero");
        }

        String detectedBoundary = detectBoundary(spoolFile, headerBoundary);
        if (headerBoundary != null && !headerBoundary.isBlank() && !headerBoundary.equals(detectedBoundary)) {
            log.warn("[DICOM Validation] Header boundary와 payload boundary가 다릅니다. payload 값을 사용합니다. header={}, payload={}",
                    headerBoundary, detectedBoundary);
        }

        ValidationState state = new ValidationState();
        try (InputStream input = Files.newInputStream(spoolFile)) {
            new MultipartParser(detectedBoundary).parse(input,
                    (partNumber, partStream) -> validatePart(partNumber, partStream, maxMetadataBytes, state));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (IOException e) {
            throw badRequest("Malformed multipart DICOM payload: " + usefulMessage(e), e);
        } catch (RuntimeException e) {
            throw badRequest("Malformed multipart DICOM payload: " + usefulMessage(e), e);
        }

        if (state.partCount == 0 || state.representativeUid.get() == null) {
            throw badRequest("Multipart payload does not contain a DICOM part");
        }

        return new ValidatedDicomPayload(state.representativeUid.get(), detectedBoundary, state.partCount);
    }

    private static void validatePart(
            int partNumber,
            MultipartInputStream partStream,
            long maxMetadataBytes,
            ValidationState state) throws IOException {
        try {
            skipMimeHeaders(partNumber, partStream);
            DicomUidResponse uid = readUids(partNumber, partStream, maxMetadataBytes);
            DicomUidResponse representative = state.representativeUid.get();
            if (representative == null) {
                state.representativeUid.set(uid);
            } else if (!representative.studyUid().equals(uid.studyUid())) {
                throw badRequest("Multipart payload contains multiple StudyInstanceUID values: "
                        + representative.studyUid() + " and " + uid.studyUid());
            }
            state.partCount++;
        } finally {
            // The DICOM scan deliberately stops before Pixel Data. Consume only to the
            // MIME boundary so MultipartParser can visit and validate every part.
            partStream.skipAll();
        }
    }

    private static void skipMimeHeaders(int partNumber, InputStream input) throws IOException {
        int consumed = 0;
        int previous = -1;
        int previousPrevious = -1;
        while (consumed < MAX_MIME_HEADER_BYTES) {
            int current = input.read();
            if (current == -1) {
                throw badRequest("Multipart part " + partNumber + " MIME headers are incomplete");
            }
            consumed++;
            if ((previousPrevious == '\r' && previous == '\n' && current == '\r')) {
                int next = input.read();
                if (next == -1) {
                    throw badRequest("Multipart part " + partNumber + " MIME headers are incomplete");
                }
                consumed++;
                if (next == '\n') {
                    return;
                }
                previousPrevious = previous;
                previous = next;
                continue;
            }
            if (previous == '\n' && current == '\n') {
                return;
            }
            previousPrevious = previous;
            previous = current;
        }
        throw badRequest("Multipart part " + partNumber + " MIME headers exceed "
                + MAX_MIME_HEADER_BYTES + " bytes");
    }

    private static DicomUidResponse readUids(
            int partNumber,
            InputStream partStream,
            long maxMetadataBytes) {
        MetadataLimitInputStream limited = new MetadataLimitInputStream(partStream, maxMetadataBytes);
        try {
            DicomInputStream dicomInput = new DicomInputStream(limited);
            dicomInput.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);
            dicomInput.setAllocateLimit((int) Math.min(Integer.MAX_VALUE, maxMetadataBytes));

            Attributes attributes = new Attributes();
            dicomInput.readFileMetaInformation();
            dicomInput.readAttributes(attributes, -1, input ->
                    input.tag() == Tag.PixelData || hasAllRequestedUids(attributes));

            String studyUid = trimToNull(attributes.getString(Tag.StudyInstanceUID));
            if (studyUid == null) {
                throw badRequest("DICOM part " + partNumber + " does not contain StudyInstanceUID");
            }

            return new DicomUidResponse(
                    studyUid,
                    trimToNull(attributes.getString(Tag.SeriesInstanceUID)),
                    trimToNull(attributes.getString(Tag.SOPInstanceUID)));
        } catch (MetadataLimitExceededException e) {
            throw badRequest("DICOM part " + partNumber + " metadata exceeds scan limit of "
                    + maxMetadataBytes + " bytes", e);
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw badRequest("DICOM part " + partNumber + " is malformed: " + usefulMessage(e), e);
        }
    }

    private static boolean hasAllRequestedUids(Attributes attributes) {
        return trimToNull(attributes.getString(Tag.StudyInstanceUID)) != null
                && trimToNull(attributes.getString(Tag.SeriesInstanceUID)) != null
                && trimToNull(attributes.getString(Tag.SOPInstanceUID)) != null;
    }

    private static String detectBoundary(Path spoolFile, String headerBoundary) throws IOException {
        int total = 0;
        String firstPayloadBoundary = null;
        try (InputStream input = Files.newInputStream(spoolFile)) {
            while (total < MAX_PREAMBLE_BYTES) {
                byte[] line = new byte[MAX_BOUNDARY_LINE_BYTES];
                int length = 0;
                boolean terminated = false;
                int value;
                while (length < line.length && total < MAX_PREAMBLE_BYTES && (value = input.read()) != -1) {
                    total++;
                    if (value == '\n') {
                        terminated = true;
                        break;
                    }
                    line[length++] = (byte) value;
                }
                if (!terminated) {
                    break;
                }
                String candidateLine = new String(line, 0, length, StandardCharsets.US_ASCII).trim();
                if (!candidateLine.startsWith("--") || candidateLine.length() <= 2) {
                    continue;
                }
                String boundary = candidateLine.substring(2);
                if (boundary.isBlank()) {
                    throw badRequest("Malformed multipart payload: boundary is empty");
                }
                boolean closingBoundary = boundary.endsWith("--");
                String normalizedBoundary = closingBoundary
                        ? boundary.substring(0, boundary.length() - 2)
                        : boundary;
                if (headerBoundary != null && headerBoundary.equals(normalizedBoundary)) {
                    if (closingBoundary) {
                        throw badRequest("Multipart payload does not contain a DICOM part");
                    }
                    return normalizedBoundary;
                }
                if (!closingBoundary && firstPayloadBoundary == null) {
                    firstPayloadBoundary = normalizedBoundary;
                }
            }
        }
        if (firstPayloadBoundary != null) {
            return firstPayloadBoundary;
        }
        throw badRequest("Malformed multipart payload: boundary line is missing or exceeds preamble limit");
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String usefulMessage(Throwable throwable) {
        return throwable.getMessage() == null || throwable.getMessage().isBlank()
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    private static ResponseStatusException badRequest(String reason, Throwable cause) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason, cause);
    }

    private static final class ValidationState {
        private final AtomicReference<DicomUidResponse> representativeUid = new AtomicReference<>();
        private int partCount;
    }

    private static final class MetadataLimitInputStream extends FilterInputStream {
        private final long limit;
        private long consumed;

        private MetadataLimitInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override
        public int read() throws IOException {
            ensureAvailable(1);
            int value = super.read();
            if (value != -1) {
                consumed++;
            }
            return value;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) throws IOException {
            if (length == 0) {
                return 0;
            }
            ensureAvailable(1);
            int allowed = (int) Math.min(length, limit - consumed);
            int count = super.read(bytes, offset, allowed);
            if (count > 0) {
                consumed += count;
            }
            return count;
        }

        @Override
        public long skip(long count) throws IOException {
            if (count <= 0) {
                return 0;
            }
            ensureAvailable(1);
            long skipped = super.skip(Math.min(count, limit - consumed));
            consumed += skipped;
            return skipped;
        }

        private void ensureAvailable(long requested) throws MetadataLimitExceededException {
            if (requested > 0 && consumed >= limit) {
                throw new MetadataLimitExceededException();
            }
        }
    }

    private static final class MetadataLimitExceededException extends IOException {
    }
}
