package dev.ioexception.dicom.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class DicomDatPackerUtilTest {

    @TempDir
    Path tempDir;

    @Test
    @DisplayName("DicomDatPackerUtil: 여러 DICOM 파일을 단일 .dat 파일로 생성 및 스트리밍 변환 테스트")
    void testDatPackerUtil() throws IOException {
        // 1. 임시 DICOM 파일 2개 생성
        File dcm1 = tempDir.resolve("test1.dcm").toFile();
        File dcm2 = tempDir.resolve("test2.dcm").toFile();

        try (FileOutputStream fos1 = new FileOutputStream(dcm1);
             FileOutputStream fos2 = new FileOutputStream(dcm2)) {
            fos1.write("DUMMY_DICOM_CONTENT_111".getBytes(StandardCharsets.UTF_8));
            fos2.write("DUMMY_DICOM_CONTENT_222".getBytes(StandardCharsets.UTF_8));
        }

        File outputDat = tempDir.resolve("output.dat").toFile();
        String boundary = "----TestBoundary123";

        // 2. .dat 파일 패키징 실행
        DicomDatPackerUtil.packDicomFilesToDatFile(List.of(dcm1, dcm2), outputDat, boundary);

        assertThat(outputDat.exists()).isTrue();
        assertThat(outputDat.length()).isGreaterThan(0);

        // 3. DAT 스트림 생성 테스트
        InputStream datStream = DicomDatPackerUtil.packDicomFilesToDatStream(List.of(dcm1, dcm2), boundary);
        byte[] datBytes = datStream.readAllBytes();
        String datContent = new String(datBytes, StandardCharsets.UTF_8);

        assertThat(datContent).contains("--" + boundary);
        assertThat(datContent).contains("DUMMY_DICOM_CONTENT_111");
        assertThat(datContent).contains("DUMMY_DICOM_CONTENT_222");
        assertThat(datContent).endsWith("--" + boundary + "--\r\n");
    }
}
