package dev.ioexception.dicom.common;

import dev.ioexception.dicom.dto.ValidatedDicomPayload;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomOutputStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DicomMultipartParserUtilTest {

    private static final String BOUNDARY = "dicom-proxy-generated-boundary";
    private static final long METADATA_LIMIT = 8L * 1024 * 1024;

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("Content-Type 헤더에서 quoted boundary를 추출한다")
    void extractsBoundaryFromContentType() {
        assertThat(DicomMultipartParserUtil.extractBoundary(
                "multipart/related; type=\"application/dicom\"; boundary=" + BOUNDARY))
                .isEqualTo(BOUNDARY);
        assertThat(DicomMultipartParserUtil.extractBoundary(
                "multipart/related; boundary=\"" + BOUNDARY + "\""))
                .isEqualTo(BOUNDARY);
        assertThat(DicomMultipartParserUtil.extractBoundary(
                "multipart/related; BOUNDARY='" + BOUNDARY + "'"))
                .isEqualTo(BOUNDARY);
    }

    @Test
    @DisplayName("같은 Study의 모든 part를 검증하고 spool 원본을 변경하지 않는다")
    void validatesAllPartsFromOneStudyWithoutChangingSpoolFile() throws Exception {
        Path payload = writeMultipart("same-study.dat", List.of(
                dicom("1.2.840.1", "1.2.840.1.1", "1.2.840.1.1.1", 2 * 1024 * 1024, 0),
                dicom("1.2.840.1", "1.2.840.1.2", "1.2.840.1.2.1", 1024, 0)));
        String checksumBefore = sha256(payload);

        ValidatedDicomPayload result = DicomMultipartParserUtil.validateSingleStudy(
                payload, "wrong-header-boundary", METADATA_LIMIT);

        assertThat(result.representativeUid().studyUid()).isEqualTo("1.2.840.1");
        assertThat(result.representativeUid().seriesUid()).isEqualTo("1.2.840.1.1");
        assertThat(result.representativeUid().sopInstanceUid()).isEqualTo("1.2.840.1.1.1");
        assertThat(result.detectedBoundary()).isEqualTo(BOUNDARY);
        assertThat(result.partCount()).isEqualTo(2);
        assertThat(sha256(payload)).isEqualTo(checksumBefore);
    }

    @Test
    @DisplayName("서로 다른 Study가 있으면 전체 multipart를 거부한다")
    void rejectsMultipleStudies() throws Exception {
        Path payload = writeMultipart("multiple-studies.dat", List.of(
                dicom("1.2.840.1", "1.2.840.1.1", "1.2.840.1.1.1", 16, 0),
                dicom("1.2.840.2", "1.2.840.2.1", "1.2.840.2.1.1", 16, 0)));

        assertBadRequest(payload, METADATA_LIMIT, "multiple StudyInstanceUID");
    }

    @Test
    @DisplayName("StudyInstanceUID가 없는 DICOM part를 거부한다")
    void rejectsPartWithoutStudyUid() throws Exception {
        Path payload = writeMultipart("missing-study.dat", List.of(
                dicom(null, "1.2.840.1.1", "1.2.840.1.1.1", 16, 0)));

        assertBadRequest(payload, METADATA_LIMIT, "does not contain StudyInstanceUID");
    }

    @Test
    @DisplayName("잘못된 DICOM part를 명확한 400 오류로 거부한다")
    void rejectsMalformedDicom() throws Exception {
        Path payload = writeMultipart("malformed.dat", List.of(
                "not-a-dicom-dataset".getBytes(StandardCharsets.US_ASCII)));

        assertBadRequest(payload, METADATA_LIMIT, "malformed");
    }

    @Test
    @DisplayName("UID보다 앞선 메타데이터가 scan limit을 넘으면 거부한다")
    void rejectsMetadataOverScanLimit() throws Exception {
        Path payload = writeMultipart("metadata-limit.dat", List.of(
                dicom("1.2.840.1", "1.2.840.1.1", "1.2.840.1.1.1", 0, 4096)));

        assertBadRequest(payload, 1024, "metadata exceeds scan limit");
    }

    @Test
    @DisplayName("boundary line이 없는 payload를 거부한다")
    void rejectsPayloadWithoutBoundaryLine() throws Exception {
        Path payload = tempDir.resolve("no-boundary.dat");
        Files.writeString(payload, "not multipart", StandardCharsets.US_ASCII);

        assertBadRequest(payload, METADATA_LIMIT, "boundary line");
    }

    @Test
    @DisplayName("과도한 MIME part header를 bounded-memory 오류로 거부한다")
    void rejectsOversizedMimePartHeader() throws Exception {
        Path payload = tempDir.resolve("oversized-mime-header.dat");
        try (OutputStream output = Files.newOutputStream(payload)) {
            output.write(("--" + BOUNDARY + "\r\nX-Large: ").getBytes(StandardCharsets.US_ASCII));
            output.write("a".repeat(70 * 1024).getBytes(StandardCharsets.US_ASCII));
            output.write(("\r\n\r\nignored\r\n--" + BOUNDARY + "--\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
        }

        assertBadRequest(payload, METADATA_LIMIT, "MIME headers exceed");
    }

    @Test
    @DisplayName("첫 boundary 앞의 bounded MIME preamble을 허용한다")
    void acceptsBoundedMimePreamble() throws Exception {
        Path original = writeMultipart("original.dat", List.of(
                dicom("1.2.840.1", "1.2.840.1.1", "1.2.840.1.1.1", 16, 0)));
        Path payload = tempDir.resolve("with-preamble.dat");
        try (OutputStream output = Files.newOutputStream(payload)) {
            output.write("MIME preamble\r\n".getBytes(StandardCharsets.US_ASCII));
            Files.copy(original, output);
        }

        ValidatedDicomPayload result = DicomMultipartParserUtil.validateSingleStudy(
                payload, BOUNDARY, METADATA_LIMIT);

        assertThat(result.representativeUid().studyUid()).isEqualTo("1.2.840.1");
    }

    @Test
    @DisplayName("preamble의 -- 시작 행보다 declared boundary를 우선한다")
    void prefersDeclaredBoundaryOverPreambleComment() throws Exception {
        Path original = writeMultipart("declared-boundary.dat", List.of(
                dicom("1.2.840.1", "1.2.840.1.1", "1.2.840.1.1.1", 16, 0)));
        Path payload = tempDir.resolve("comment-preamble.dat");
        try (OutputStream output = Files.newOutputStream(payload)) {
            output.write("--comment in a legal MIME preamble\r\n".getBytes(StandardCharsets.US_ASCII));
            Files.copy(original, output);
        }

        ValidatedDicomPayload result = DicomMultipartParserUtil.validateSingleStudy(
                payload, BOUNDARY, METADATA_LIMIT);

        assertThat(result.detectedBoundary()).isEqualTo(BOUNDARY);
        assertThat(result.representativeUid().studyUid()).isEqualTo("1.2.840.1");
    }

    private void assertBadRequest(Path payload, long limit, String expectedReason) {
        assertThatThrownBy(() -> DicomMultipartParserUtil.validateSingleStudy(payload, BOUNDARY, limit))
                .isInstanceOfSatisfying(ResponseStatusException.class, exception -> {
                    assertThat(exception.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
                    assertThat(exception.getReason()).containsIgnoringCase(expectedReason);
                });
    }

    private Path writeMultipart(String filename, List<byte[]> parts) throws IOException {
        Path payload = tempDir.resolve(filename);
        try (OutputStream output = Files.newOutputStream(payload)) {
            for (byte[] part : parts) {
                output.write(("--" + BOUNDARY + "\r\n"
                        + "Content-Type: application/dicom\r\n"
                        + "Content-Length: " + part.length + "\r\n\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
                output.write(part);
                output.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            }
            output.write(("--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.US_ASCII));
        }
        return payload;
    }

    private byte[] dicom(
            String studyUid,
            String seriesUid,
            String sopInstanceUid,
            int pixelDataBytes,
            int metadataBytes) throws IOException {
        Attributes attributes = new Attributes();
        attributes.setString(Tag.SOPClassUID, VR.UI, UID.SecondaryCaptureImageStorage);
        attributes.setString(Tag.SOPInstanceUID, VR.UI, sopInstanceUid);
        if (metadataBytes > 0) {
            attributes.setBytes(Tag.PatientComments, VR.LT, new byte[metadataBytes]);
        }
        if (studyUid != null) {
            attributes.setString(Tag.StudyInstanceUID, VR.UI, studyUid);
        }
        attributes.setString(Tag.SeriesInstanceUID, VR.UI, seriesUid);
        if (pixelDataBytes > 0) {
            attributes.setBytes(Tag.PixelData, VR.OB, new byte[pixelDataBytes]);
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DicomOutputStream output = new DicomOutputStream(bytes, UID.ExplicitVRLittleEndian)) {
            output.writeDataset(attributes.createFileMetaInformation(UID.ExplicitVRLittleEndian), attributes);
        }
        return bytes.toByteArray();
    }

    private String sha256(Path path) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        try (var input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }
}
