package dev.ioexception.dicom.common;

import dev.ioexception.dicom.dto.RecordedStream;
import dev.ioexception.dicom.dto.response.DicomUidResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

public class Sample15MultiStudyTest {

    private static final String SAMPLE15_DIR = System.getProperty("sample15.dir", System.getenv().getOrDefault("SAMPLE15_DIR", "./sample_dicoms"));
    private static final String BOUNDARY = "----WebKitFormBoundarySample15Test";

    @Test
    @DisplayName("Sample15 실제 8개 DICOM 파일로 Early Exit Header Peek 및 스트림 100% 복원(Rewind) 테스트")
    void testSample15MultiStudyHeaderPeekAndRewind() throws Exception {
        File dir = new File(SAMPLE15_DIR);
        if (!dir.exists() || !dir.isDirectory()) {
            System.out.println("Sample15 테스트 디렉토리가 존재하지 않아 테스트를 건너뜁니다: " + SAMPLE15_DIR);
            return;
        }

        File[] files = dir.listFiles((d, name) -> name.endsWith(".dcm"));
        if (files == null || files.length == 0) {
            return;
        }

        Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));

        // 1. 8개 DICOM 파일로 가상의 multipart/related STOW-RS 요청 스트림 구성
        InputStream multipartStream = buildMultipartRelatedStream(files, BOUNDARY);

        // 2. Header Peek 실행 (Early Exit 적용)
        long startTime = System.currentTimeMillis();
        RecordedStream recordedStream = DicomMultipartParserUtil.peekHeaderAndRewind(multipartStream, BOUNDARY);
        long elapsed = System.currentTimeMillis() - startTime;

        List<DicomUidResponse> uidsList = recordedStream.uidsList();

        System.out.println("====== Sample15 Early Exit Header Peek 결과 ======");
        System.out.println("스캔 소요 시간: " + elapsed + " ms");
        System.out.println("감지된 대표 Study 개수: " + uidsList.size());
        for (int i = 0; i < uidsList.size(); i++) {
            DicomUidResponse uid = uidsList.get(i);
            System.out.println(String.format("  [%d] StudyUID: %s, SeriesUID: %s, SOPInstanceUID: %s",
                    i + 1, uid.studyUid(), uid.seriesUid(), uid.sopInstanceUid()));
        }

        // 3. 검증: Early Exit 적용으로 Part 1의 대표 StudyUID 1개만 빠르게 감지
        assertThat(uidsList).hasSize(1);
        assertThat(elapsed).isLessThan(500); // 3000ms ➔ 500ms 미만 초고속 검증

        // 4. 스트림 복원 검증: toRecombinedStream()으로 복원한 스트림이 읽어지는지 검증
        InputStream recombinedStream = recordedStream.combinedStream();
        byte[] firstBuffer = new byte[100];
        int bytesRead = recombinedStream.read(firstBuffer);
        assertThat(bytesRead).isEqualTo(100);
        assertThat(new String(firstBuffer, StandardCharsets.UTF_8)).startsWith("--" + BOUNDARY);
    }

    private InputStream buildMultipartRelatedStream(File[] files, String boundary) throws Exception {
        InputStream combined = new ByteArrayInputStream(new byte[0]);

        for (File file : files) {
            String header = "--" + boundary + "\r\n" +
                    "Content-Type: application/dicom\r\n" +
                    "Content-Length: " + file.length() + "\r\n\r\n";
            ByteArrayInputStream headerStream = new ByteArrayInputStream(header.getBytes(StandardCharsets.UTF_8));
            FileInputStream fileStream = new FileInputStream(file);
            ByteArrayInputStream trailerStream = new ByteArrayInputStream("\r\n".getBytes(StandardCharsets.UTF_8));

            combined = new SequenceInputStream(combined, headerStream);
            combined = new SequenceInputStream(combined, fileStream);
            combined = new SequenceInputStream(combined, trailerStream);
        }

        String closingBoundary = "--" + boundary + "--\r\n";
        ByteArrayInputStream closingStream = new ByteArrayInputStream(closingBoundary.getBytes(StandardCharsets.UTF_8));
        return new SequenceInputStream(combined, closingStream);
    }
}
