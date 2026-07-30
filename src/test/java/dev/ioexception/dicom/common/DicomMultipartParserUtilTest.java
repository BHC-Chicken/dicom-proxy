package dev.ioexception.dicom.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

public class DicomMultipartParserUtilTest {

    @Test
    @DisplayName("Content-Type 헤더에서 boundary 문자열 추출 테스트")
    void testExtractBoundary() {
        String contentType1 = "multipart/related; type=\"application/dicom\"; boundary=----Boundary123";
        String contentType2 = "multipart/related; type=\"application/dicom\"; boundary=\"----Boundary456\"";

        assertThat(DicomMultipartParserUtil.extractBoundary(contentType1)).isEqualTo("----Boundary123");
        assertThat(DicomMultipartParserUtil.extractBoundary(contentType2)).isEqualTo("----Boundary456");
    }

    @Test
    @DisplayName("RecordingInputStream 복원 테스트")
    void testRecordingInputStreamRecombine() throws IOException {
        byte[] originalData = "STOW_RS_RAW_PAYLOAD_DATA_123456789".getBytes(StandardCharsets.UTF_8);
        ByteArrayInputStream bais = new ByteArrayInputStream(originalData);

        RecordingInputStream ris = new RecordingInputStream(bais);
        // 일부 헤더 바이트 스캔 (10바이트 읽음)
        byte[] buffer = new byte[10];
        ris.read(buffer);

        InputStream recombined = ris.toRecombinedStream();
        byte[] allRecombinedBytes = recombined.readAllBytes();

        assertThat(allRecombinedBytes).isEqualTo(originalData);
    }
}
