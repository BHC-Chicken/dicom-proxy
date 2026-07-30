package dev.ioexception.dicom.service;

import co.elastic.apm.api.CaptureSpan;
import dev.ioexception.dicom.common.DicomDatPackerUtil;
import dev.ioexception.dicom.common.DicomMultipartParserUtil;
import dev.ioexception.dicom.common.DicomXmlParserUtil;
import dev.ioexception.dicom.config.dicom.DicomDcmClient;
import dev.ioexception.dicom.dto.MetadataFormat;
import dev.ioexception.dicom.dto.RecordedStream;
import dev.ioexception.dicom.dto.response.DicomForwardResponse;
import dev.ioexception.dicom.dto.response.DicomUidResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class DicomWebService {

    private final DicomDcmClient dicomDcmClient;
    private final RestClient restClient;
    private final Semaphore forwardSemaphore;
    private final String targetBaseUrl;
    private final int maxPermits;

    public DicomWebService(
            DicomDcmClient dicomDcmClient,
            RestClient proxyRestClient,
            @Value("${base-url:http://localhost:8080}") String targetBaseUrl,
            @Value("${dicom.max-concurrency:${MAX_DICOM_REQUEST:10}}") int maxPermits) {
        this.dicomDcmClient = dicomDcmClient;
        this.restClient = proxyRestClient;
        this.targetBaseUrl = targetBaseUrl;
        this.maxPermits = maxPermits;
        this.forwardSemaphore = new Semaphore(maxPermits);
        log.info("[DicomWebService] 초기화 완료 - Target: {}, MAX_DICOM_REQUEST Semaphore Permits: {}", targetBaseUrl, maxPermits);
    }

    public List<DicomForwardResponse> processAndForwardDicomAsync(String sourceId, List<MultipartFile> files, HttpServletRequest request) {
        log.info("[Proxy] 수신 처리 스레드: {} (VirtualThread 여부: {})",
                Thread.currentThread().getName(), Thread.currentThread().isVirtual());

        if (files != null && !files.isEmpty()) {
            List<MultipartFile> validFiles = files.stream()
                    .filter(f -> f != null && !f.isEmpty())
                    .toList();

            if (!validFiles.isEmpty()) {
                log.info("[Proxy] 다중 .dat 첨부 파일 수신 (총 {} 개 파일), SourceID: {}", validFiles.size(), sourceId);
                return processMultipleUploadedDatFilesConcurrently(sourceId, validFiles);
            }
        }

        String contentType = request.getContentType();
        log.info("[Proxy] DICOM 다이렉트 스트림 중계 수신 - Content-Type: {}, SourceID: {}", contentType, sourceId);

        return processMultipartRelatedProxy(sourceId, request);
    }

    private List<DicomForwardResponse> processMultipleUploadedDatFilesConcurrently(String sourceId, List<MultipartFile> files) {
        log.info("[VirtualThread] {} 개의 .dat 파일 각각에 가상 스레드 1개씩 할당하여 병열 Zero-Copy 중계 시작", files.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<DicomForwardResponse>>> futures = files.stream()
                    .map(file -> CompletableFuture.supplyAsync(() -> {
                        log.info("[VirtualThread Task] .dat 파일 처리 시작 - 파일명: {}, 스레드: {}",
                                file.getOriginalFilename(), Thread.currentThread().getName());
                        return processUploadedDatFileProxy(sourceId, file);
                    }, executor))
                    .toList();

            return futures.stream()
                    .map(CompletableFuture::join)
                    .flatMap(List::stream)
                    .toList();
        }
    }

    private List<DicomForwardResponse> processUploadedDatFileProxy(String sourceId, MultipartFile file) {
        try {
            String boundary = DicomMultipartParserUtil.extractBoundary(file.getContentType());
            if (boundary == null || boundary.isBlank()) {
                boundary = DicomDatPackerUtil.DEFAULT_BOUNDARY;
            }

            RecordedStream recordedStream = DicomMultipartParserUtil.peekHeaderAndRewind(file.getInputStream(), boundary);

            List<DicomUidResponse> uidsList = recordedStream.uidsList();
            String primaryStudyUid = uidsList.getFirst().studyUid();
            String effectiveBoundary = recordedStream.detectedBoundary();

            String contentType = "multipart/related; type=\"application/dicom\"; boundary=" + effectiveBoundary;

            log.info("[Proxy] [.dat 파일: {}] Target 서버로 Zero-Copy 파이프 스트리밍 중계 시작 (Boundary: {}, 감지된 Study 수: {}, 대표 StudyUID: {})",
                    file.getOriginalFilename(), effectiveBoundary, uidsList.size(), primaryStudyUid);
            String forwardStatus = forwardStreamWithSemaphore(recordedStream.combinedStream(), contentType, primaryStudyUid, sourceId);

            return uidsList.stream()
                    .map(uids -> new DicomForwardResponse(
                            uids.studyUid(),
                            uids.seriesUid(),
                            uids.sopInstanceUid(),
                            List.of(String.format("[StudyUID: %s] %s", uids.studyUid(), forwardStatus))
                    ))
                    .toList();
        } catch (Exception e) {
            log.error("[Proxy] 첨부 파일({}) 중계 처리 실패", file.getOriginalFilename(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "첨부 파일 중계 실패: " + e.getMessage());
        }
    }

    private List<DicomForwardResponse> processMultipartRelatedProxy(String sourceId, HttpServletRequest request) {
        String boundary = DicomMultipartParserUtil.extractBoundary(request.getContentType());
        if (boundary == null || boundary.isBlank()) {
            boundary = DicomDatPackerUtil.DEFAULT_BOUNDARY;
        }

        RecordedStream recordedStream = executeHeaderPeek(request, boundary);

        List<DicomUidResponse> uidsList = recordedStream.uidsList();
        String primaryStudyUid = uidsList.getFirst().studyUid();
        String effectiveBoundary = recordedStream.detectedBoundary();

        String contentType = "multipart/related; type=\"application/dicom\"; boundary=" + effectiveBoundary;

        log.info("[Proxy] Target 서버로 Zero-Copy 파이프 스트리밍 중계 시작 (Boundary: {}, 감지된 Study 수: {}, 대표 StudyUID: {})",
                effectiveBoundary, uidsList.size(), primaryStudyUid);
        String forwardStatus = forwardStreamWithSemaphore(recordedStream.combinedStream(), contentType, primaryStudyUid, sourceId);

        log.info("[Proxy] DICOM 중계 처리 완료 (감지된 Study 수: {})", uidsList.size());

        return uidsList.stream()
                .map(uids -> new DicomForwardResponse(
                        uids.studyUid(),
                        uids.seriesUid(),
                        uids.sopInstanceUid(),
                        List.of(String.format("[StudyUID: %s] %s", uids.studyUid(), forwardStatus))
                ))
                .toList();
    }

    @CaptureSpan(value = "STOW-RS Header Peek", type = "proxy", subtype = "dicom")
    private RecordedStream executeHeaderPeek(HttpServletRequest request, String boundary) {
        try {
            return DicomMultipartParserUtil.peekHeaderAndRewind(request.getInputStream(), boundary);
        } catch (ResponseStatusException rse) {
            throw rse;
        } catch (Exception e) {
            log.error("[Proxy] DICOM Header Peek 중 오류 발생", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DICOM 헤더 파싱 실패: " + e.getMessage());
        }
    }

    private String forwardStreamWithSemaphore(InputStream combinedStream, String contentType, String studyUid, String sourceId) {
        try {
            log.debug("[Semaphore] 점유 대기 시작 (현재 이용가능 Permits: {}/{})", forwardSemaphore.availablePermits(), maxPermits);
            forwardSemaphore.acquire();
            log.info("[Semaphore] 허가 획득 완료 (남은 Permits: {}/{}) - StudyUID: {}", forwardSemaphore.availablePermits(), maxPermits, studyUid);

            return streamToTargetServer(combinedStream, contentType, studyUid, sourceId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Semaphore] 전송 대기 중 스레드 중단 (StudyUID: {})", studyUid, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DICOM 전송 대기 중 인터럽트 발생");
        } finally {
            forwardSemaphore.release();
            log.debug("[Semaphore] 반납 완료 (현재 이용가능 Permits: {}/{}) - StudyUID: {}", forwardSemaphore.availablePermits(), maxPermits, studyUid);
        }
    }

    @CaptureSpan(value = "STOW-RS Target Forwarding", type = "ext", subtype = "http")
    private String streamToTargetServer(InputStream combinedStream, String contentType, String studyUid, String sourceId) {
        log.info("[RestClient] Zero-Copy 스트림 전송 중... -> Target: {}/hime-server/dcm/studies/{}, Content-Type: {}", targetBaseUrl, studyUid, contentType);

        try {
            String responseBody = restClient.post()
                    .uri(targetBaseUrl + "/hime-server/dcm/studies/{studyUid}?SourceID={sourceId}", studyUid, sourceId)
                    .contentType(MediaType.parseMediaType(contentType))
                    .accept(MediaType.parseMediaType("application/dicom+xml"))
                    .body(combinedStream::transferTo)
                    .retrieve()
                    .body(String.class);

            String parsedResult = DicomXmlParserUtil.extractSuccessInfo(responseBody);
            log.info("[RestClient] [StudyUID: {}] STOW-RS Target 서버 전송 성공: {}", studyUid, parsedResult);
            return String.format("성공 -> %s", parsedResult);
        } catch (Exception e) {
            log.error("[RestClient] [StudyUID: {}] STOW-RS Target 서버 전송 실패", studyUid, e);
            return String.format("실패 -> %s", e.getMessage());
        }
    }

    public byte[] getWadoImage(String studyUID, String seriesUID, String objectUID, String sourceId, String contentType) {
        return dicomDcmClient.getWadoImage("WADO", studyUID, seriesUID, objectUID, contentType, sourceId);
    }

    public byte[] downloadStudyZip(String studyUID, String patientId) {
        return dicomDcmClient.downloadStudyZip(studyUID, patientId);
    }

    public String retrieveStudyMetadata(String studyUID, String patientId, MetadataFormat format, String includePrivate, String groups, String xsl) {
        return dicomDcmClient.retrieveStudyMetadata(studyUID, format.getAcceptHeader(), patientId, includePrivate, groups, xsl);
    }

    public void createDicomManifestKOS(String studyUid, String kosUid, String sourceId, boolean hasReport, Integer totalInstanceCount) {
        String hasReportStr = hasReport ? "true" : "false";
        String totalCountStr = totalInstanceCount != null ? String.valueOf(totalInstanceCount) : null;
        dicomDcmClient.createDicomManifestKOS(studyUid, kosUid, sourceId, hasReportStr, totalCountStr);
    }
}
