package dev.ioexception.dicom.common;

import dev.ioexception.dicom.dto.RecordedStream;
import dev.ioexception.dicom.dto.response.DicomUidResponse;
import lombok.extern.slf4j.Slf4j;
import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.mime.MultipartInputStream;
import org.dcm4che3.mime.MultipartParser;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
public class DicomMultipartParserUtil {

    private static class EarlyExitScanException extends RuntimeException {
    }

    public static String extractBoundary(String contentType) {
        if (contentType == null || contentType.isBlank()) {
            return null;
        }

        String[] tokens = contentType.split(";");
        for (String token : tokens) {
            token = token.trim();
            if (token.toLowerCase().startsWith("boundary=")) {
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

        log.warn("[DicomMultipartParserUtil] Content-Type 헤더에서 Boundary를 찾을 수 없습니다: {}", contentType);
        return null;
    }

    public static RecordedStream peekHeaderAndRewind(InputStream requestStream, String headerBoundary)
            throws IOException {
        RecordingInputStream recordingStream = new RecordingInputStream(requestStream);

        ByteArrayOutputStream firstLineBuffer = new ByteArrayOutputStream();
        String detectedBoundary = readFirstLineAndDetectBoundary(recordingStream, firstLineBuffer, headerBoundary);
        validateBoundary(detectedBoundary);

        log.info("[Header Peek] DICOM 메타데이터 Early Exit 스캔 시작 (Boundary: {})", detectedBoundary);

        InputStream parserInput = new SequenceInputStream(
                new ByteArrayInputStream(firstLineBuffer.toByteArray()),
                recordingStream);

        List<DicomUidResponse> uidsList = scanAllPartHeaders(parserInput, detectedBoundary);

        log.info("[Header Peek] Early Exit 성공 완료 - 대표 StudyUID 감지됨 ({}), 캡처된 헤더 크기: {} bytes, 스트림 복원(Rewind) 수행",
                uidsList.getFirst().studyUid(), recordingStream.getRecordedSize());
        return new RecordedStream(uidsList, recordingStream.toRecombinedStream(), detectedBoundary);
    }

    private static String readFirstLineAndDetectBoundary(RecordingInputStream recordingStream,
            ByteArrayOutputStream firstLineBuffer, String headerBoundary) {
        try {
            int b;
            while ((b = recordingStream.read()) != -1) {
                firstLineBuffer.write(b);
                if (b == '\n' || firstLineBuffer.size() >= 512) {
                    break;
                }
            }
            String firstLine = firstLineBuffer.toString(StandardCharsets.UTF_8).trim();
            if (firstLine.startsWith("--")) {
                String boundaryCandidate = firstLine.substring(2).trim();
                if (boundaryCandidate.endsWith("--")) {
                    boundaryCandidate = boundaryCandidate.substring(0, boundaryCandidate.length() - 2);
                }
                if (!boundaryCandidate.isBlank()) {
                    log.info("[DicomMultipartParserUtil] 스트림 첫 라인에서 Boundary 자동 감지 성공: {}", boundaryCandidate);
                    return boundaryCandidate;
                }
            }
        } catch (Exception e) {
            log.debug("[DicomMultipartParserUtil] 첫 라인 Boundary 자동 감지 시도 실패: {}", e.getMessage());
        }

        if (headerBoundary != null && !headerBoundary.isBlank()) {
            return headerBoundary;
        }

        return DicomDatPackerUtil.DEFAULT_BOUNDARY;
    }

    private static void validateBoundary(String boundary) {
        if (boundary == null || boundary.isBlank()) {
            throw new IllegalArgumentException("Boundary 정보가 Content-Type 헤더에 존재하지 않습니다.");
        }
    }

    private static List<DicomUidResponse> scanAllPartHeaders(InputStream recordingStream, String boundary) {
        Map<String, DicomUidResponse> studyMap = new LinkedHashMap<>();

        try {
            new MultipartParser(boundary).parse(recordingStream, (partNumber, multipartInputStream) -> {
                extractUidFromPart(partNumber, multipartInputStream, studyMap);
                if (!studyMap.isEmpty()) {
                    throw new EarlyExitScanException();
                }
            });
        } catch (EarlyExitScanException e) {
            log.info("[Header Peek] Early Exit 실행: 첫 번째 대표 Study 헤더 감지 즉시 스캔 종료");
        } catch (Exception e) {
            log.debug("[Header Peek] 헤더 스캔 완료 또는 종료: {}", e.getMessage());
        }

        if (studyMap.isEmpty()) {
            log.error("[Header Peek] DICOM 메타데이터 (StudyInstanceUID) 추출 실패");
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DICOM 메타데이터 (StudyInstanceUID)를 찾을 수 없습니다.");
        }

        return new ArrayList<>(studyMap.values());
    }

    private static void extractUidFromPart(int partNumber, InputStream inputStream, Map<String, DicomUidResponse> studyMap) {
        try {
            if (inputStream instanceof MultipartInputStream mis) {
                mis.readHeaderParams();
            }
            try (DicomInputStream dis = new DicomInputStream(inputStream)) {
                dis.setIncludeBulkData(DicomInputStream.IncludeBulkData.NO);
                Attributes attrs = dis.readDataset();
                parseAndSetUid(partNumber, attrs, studyMap);
            }
        } catch (Exception e) {
            log.warn("[Header Peek] Part {} 파싱 중 경고: {}", partNumber, e.getMessage());
        }
    }

    private static void parseAndSetUid(int partNumber, Attributes attrs, Map<String, DicomUidResponse> studyMap) {
        if (attrs == null) {
            return;
        }

        String studyUid = attrs.getString(Tag.StudyInstanceUID);
        String seriesUid = attrs.getString(Tag.SeriesInstanceUID);
        String sopInstanceUid = attrs.getString(Tag.SOPInstanceUID);

        if (studyUid != null && !studyUid.isBlank()) {
            boolean isNewStudy = !studyMap.containsKey(studyUid);
            studyMap.putIfAbsent(studyUid, new DicomUidResponse(studyUid, seriesUid, sopInstanceUid));
            if (isNewStudy) {
                log.info("[Header Peek] 대표 Study 감지 [Part {}] - StudyUID: {}, SeriesUID: {}, SOPInstanceUID: {}",
                        partNumber, studyUid, seriesUid, sopInstanceUid);
            }
        }
    }
}
