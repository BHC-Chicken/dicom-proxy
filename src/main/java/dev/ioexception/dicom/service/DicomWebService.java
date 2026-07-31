package dev.ioexception.dicom.service;

import co.elastic.apm.api.CaptureSpan;
import dev.ioexception.dicom.common.DicomDatPackerUtil;
import dev.ioexception.dicom.common.DicomMultipartParserUtil;
import dev.ioexception.dicom.common.DicomXmlParserUtil;
import dev.ioexception.dicom.config.dicom.DicomDcmClient;
import dev.ioexception.dicom.dto.MetadataFormat;
import dev.ioexception.dicom.dto.ValidatedDicomPayload;
import dev.ioexception.dicom.dto.response.DicomForwardResponse;
import dev.ioexception.dicom.dto.response.DicomStreamResponse;
import dev.ioexception.dicom.dto.response.DicomUidResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.util.UriComponentsBuilder;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Slf4j
@Service
public class DicomWebService {

    private final DicomDcmClient dicomDcmClient;
    private final RestClient restClient;
    private final HttpClient httpClient;
    private final Semaphore forwardSemaphore;
    private final Semaphore spoolSemaphore;
    private final String targetBaseUrl;
    private final int maxPermits;
    private final Path spoolDirectory;
    private final long maxSpoolBytes;
    private final long maxMetadataScanBytes;
    private final int maxStowResponseBytes;
    private final Duration staleSpoolRetention;

    public DicomWebService(
            DicomDcmClient dicomDcmClient,
            RestClient proxyRestClient,
            HttpClient dicomHttpClient,
            @Value("${base-url:http://localhost:8080}") String targetBaseUrl,
            @Value("${dicom.max-concurrency:${MAX_DICOM_REQUEST:10}}") int maxPermits,
            @Value("${dicom.forward.spool-dir:${java.io.tmpdir}/dicom-proxy-forward}") String spoolDirectory,
            @Value("${dicom.forward.max-spool-size:5GB}") DataSize maxSpoolSize,
            @Value("${dicom.forward.max-metadata-scan-size:8MB}") DataSize maxMetadataScanSize,
            @Value("${dicom.forward.max-stow-response-size:1MB}") DataSize maxStowResponseSize,
            @Value("${dicom.forward.max-concurrent-spools:2}") int maxConcurrentSpools,
            @Value("${dicom.forward.stale-spool-retention:24h}") Duration staleSpoolRetention) {
        if (maxPermits < 1) {
            throw new IllegalArgumentException("dicom.max-concurrency는 1 이상이어야 합니다.");
        }
        if (maxConcurrentSpools < 1) {
            throw new IllegalArgumentException("dicom.forward.max-concurrent-spools는 1 이상이어야 합니다.");
        }
        if (maxStowResponseSize.toBytes() < 1 || maxStowResponseSize.toBytes() >= Integer.MAX_VALUE) {
            throw new IllegalArgumentException("dicom.forward.max-stow-response-size는 1 byte 이상 2GB 미만이어야 합니다.");
        }
        this.dicomDcmClient = dicomDcmClient;
        this.restClient = proxyRestClient;
        this.httpClient = dicomHttpClient;
        this.targetBaseUrl = targetBaseUrl;
        this.maxPermits = maxPermits;
        this.spoolDirectory = Paths.get(spoolDirectory).toAbsolutePath().normalize();
        this.maxSpoolBytes = maxSpoolSize.toBytes();
        this.maxMetadataScanBytes = maxMetadataScanSize.toBytes();
        this.maxStowResponseBytes = (int) maxStowResponseSize.toBytes();
        this.staleSpoolRetention = staleSpoolRetention;
        this.forwardSemaphore = new Semaphore(maxPermits);
        this.spoolSemaphore = new Semaphore(maxConcurrentSpools);
        log.info("[DicomWebService] 초기화 완료 - Target: {}, Permits: {}, SpoolDir: {}, MaxSpool: {} bytes",
                targetBaseUrl, maxPermits, this.spoolDirectory, this.maxSpoolBytes);
    }

    @PostConstruct
    void cleanupStaleSpoolFiles() {
        try {
            Files.createDirectories(spoolDirectory);
            Instant cutoff = Instant.now().minus(staleSpoolRetention);
            try (var files = Files.list(spoolDirectory)) {
                files.filter(path -> path.getFileName().toString().startsWith("dicom-forward-"))
                        .filter(path -> path.getFileName().toString().endsWith(".part"))
                        .filter(path -> {
                            try {
                                return Files.getLastModifiedTime(path).toInstant().isBefore(cutoff);
                            } catch (IOException e) {
                                return false;
                            }
                        })
                        .forEach(this::deleteSpoolFile);
            }
        } catch (IOException e) {
            log.warn("[Proxy] 오래된 spool 파일 정리 실패: {}", spoolDirectory, e);
        }
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
        log.info("[VirtualThread] {} 개의 .dat 파일 각각에 가상 스레드 1개씩 할당하여 bounded-memory 중계 시작", files.size());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            List<CompletableFuture<List<DicomForwardResponse>>> futures = files.stream()
                    .map(file -> CompletableFuture.supplyAsync(() -> {
                        log.info("[VirtualThread Task] .dat 파일 처리 시작 - 파일명: {}, 스레드: {}",
                                file.getOriginalFilename(), Thread.currentThread().getName());
                        return processUploadedDatFileProxy(sourceId, file);
                    }, executor))
                    .toList();

            return futures.stream()
                    .map(this::joinForwardResult)
                    .flatMap(List::stream)
                    .toList();
        }
    }

    private List<DicomForwardResponse> joinForwardResult(
            CompletableFuture<List<DicomForwardResponse>> future) {
        try {
            return future.join();
        } catch (CompletionException e) {
            if (e.getCause() instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw e;
        }
    }

    private List<DicomForwardResponse> processUploadedDatFileProxy(String sourceId, MultipartFile file) {
        Path spoolFile = null;
        boolean spoolPermitAcquired = false;
        try {
            acquireSpoolPermit();
            spoolPermitAcquired = true;
            String boundary = DicomMultipartParserUtil.extractBoundary(file.getContentType());
            if (boundary == null || boundary.isBlank()) {
                boundary = DicomDatPackerUtil.DEFAULT_BOUNDARY;
            }
            try (InputStream input = file.getInputStream()) {
                spoolFile = spoolPayload(input);
            }
            return validateAndForwardSpool(sourceId, spoolFile, boundary, file.getOriginalFilename());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Proxy] 첨부 파일({}) 중계 처리 실패", file.getOriginalFilename(), e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "첨부 파일 중계 실패: " + e.getMessage());
        } finally {
            deleteSpoolFile(spoolFile);
            if (spoolPermitAcquired) {
                spoolSemaphore.release();
            }
        }
    }

    private List<DicomForwardResponse> processMultipartRelatedProxy(String sourceId, HttpServletRequest request) {
        String boundary = DicomMultipartParserUtil.extractBoundary(request.getContentType());
        if (boundary == null || boundary.isBlank()) {
            boundary = DicomDatPackerUtil.DEFAULT_BOUNDARY;
        }

        Path spoolFile = null;
        boolean spoolPermitAcquired = false;
        try {
            acquireSpoolPermit();
            spoolPermitAcquired = true;
            try (InputStream input = request.getInputStream()) {
                spoolFile = spoolPayload(input);
            }
            return validateAndForwardSpool(sourceId, spoolFile, boundary, "direct-request");
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("[Proxy] DICOM spool/검증 중 오류 발생", e);
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DICOM 요청 검증 실패: " + e.getMessage());
        } finally {
            deleteSpoolFile(spoolFile);
            if (spoolPermitAcquired) {
                spoolSemaphore.release();
            }
        }
    }

    private void acquireSpoolPermit() {
        try {
            spoolSemaphore.acquire();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
                    "DICOM spool 대기 중 인터럽트 발생", e);
        }
    }

    @CaptureSpan(value = "STOW-RS Spool Validation", type = "proxy", subtype = "dicom")
    private List<DicomForwardResponse> validateAndForwardSpool(
            String sourceId, Path spoolFile, String boundary, String sourceName) throws IOException {
        ValidatedDicomPayload payload = DicomMultipartParserUtil.validateSingleStudy(
                spoolFile, boundary, maxMetadataScanBytes);
        DicomUidResponse uid = payload.representativeUid();
        String contentType = "multipart/related; type=\"application/dicom\"; boundary=" + payload.detectedBoundary();

        log.info("[Proxy] [{}] 검증 완료 후 bounded-memory streaming 시작 (Boundary: {}, Part 수: {}, StudyUID: {})",
                sourceName, payload.detectedBoundary(), payload.partCount(), uid.studyUid());

        String forwardStatus;
        try (InputStream input = Files.newInputStream(spoolFile)) {
            forwardStatus = forwardStreamWithSemaphore(
                    input, Files.size(spoolFile), contentType, uid.studyUid(), sourceId);
        }
        return List.of(new DicomForwardResponse(
                uid.studyUid(), uid.seriesUid(), uid.sopInstanceUid(),
                List.of(String.format("[StudyUID: %s] %s", uid.studyUid(), forwardStatus))));
    }

    private Path spoolPayload(InputStream input) throws IOException {
        Files.createDirectories(spoolDirectory);
        Path spoolFile = Files.createTempFile(spoolDirectory, "dicom-forward-", ".part");
        long total = 0;
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream output = Files.newOutputStream(spoolFile)) {
            int read;
            while ((read = input.read(buffer)) != -1) {
                total += read;
                if (total > maxSpoolBytes) {
                    throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                            "DICOM payload가 spool 제한을 초과했습니다.");
                }
                output.write(buffer, 0, read);
            }
            return spoolFile;
        } catch (IOException | RuntimeException e) {
            Files.deleteIfExists(spoolFile);
            throw e;
        }
    }

    private void deleteSpoolFile(Path spoolFile) {
        if (spoolFile == null) {
            return;
        }
        try {
            Files.deleteIfExists(spoolFile);
        } catch (IOException e) {
            log.warn("[Proxy] spool 파일 삭제 실패: {}", spoolFile, e);
        }
    }

    private String forwardStreamWithSemaphore(InputStream combinedStream, long contentLength, String contentType, String studyUid, String sourceId) {
        boolean acquired = false;
        try {
            log.debug("[Semaphore] 점유 대기 시작 (현재 이용가능 Permits: {}/{})", forwardSemaphore.availablePermits(), maxPermits);
            forwardSemaphore.acquire();
            acquired = true;
            log.info("[Semaphore] 허가 획득 완료 (남은 Permits: {}/{}) - StudyUID: {}", forwardSemaphore.availablePermits(), maxPermits, studyUid);

            return streamToTargetServer(combinedStream, contentLength, contentType, studyUid, sourceId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("[Semaphore] 전송 대기 중 스레드 중단 (StudyUID: {})", studyUid, e);
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DICOM 전송 대기 중 인터럽트 발생");
        } finally {
            if (acquired) {
                forwardSemaphore.release();
                log.debug("[Semaphore] 반납 완료 (현재 이용가능 Permits: {}/{}) - StudyUID: {}", forwardSemaphore.availablePermits(), maxPermits, studyUid);
            }
        }
    }

    @CaptureSpan(value = "STOW-RS Target Forwarding", type = "ext", subtype = "http")
    private String streamToTargetServer(InputStream combinedStream, long contentLength, String contentType, String studyUid, String sourceId) {
        log.info("[RestClient] 파일 기반 스트림 전송 중... -> Target: {}/hime-server/dcm/studies/{}, Content-Type: {}", targetBaseUrl, studyUid, contentType);

        try {
            String responseBody = restClient.post()
                    .uri(targetBaseUrl + "/hime-server/dcm/studies/{studyUid}?SourceID={sourceId}", studyUid, sourceId)
                    .contentType(MediaType.parseMediaType(contentType))
                    .contentLength(contentLength)
                    .accept(MediaType.parseMediaType("application/dicom+xml"))
                    .body(combinedStream::transferTo)
                    .exchange((request, response) -> {
                        if (!response.getStatusCode().is2xxSuccessful()) {
                            throw new IOException("Target STOW-RS returned HTTP " + response.getStatusCode().value());
                        }
                        Charset charset = Optional.ofNullable(response.getHeaders().getContentType())
                                .map(MediaType::getCharset)
                                .orElse(StandardCharsets.UTF_8);
                        return readBoundedStowResponse(response.getBody(), charset);
                    });

            String parsedResult = DicomXmlParserUtil.extractSuccessInfo(responseBody);
            log.info("[RestClient] [StudyUID: {}] STOW-RS Target 서버 전송 성공: {}", studyUid, parsedResult);
            return String.format("성공 -> %s", parsedResult);
        } catch (Exception e) {
            log.error("[RestClient] [StudyUID: {}] STOW-RS Target 서버 전송 실패", studyUid, e);
            return String.format("실패 -> %s", e.getMessage());
        }
    }

    private String readBoundedStowResponse(InputStream input, Charset charset) throws IOException {
        byte[] responseBytes = input.readNBytes(maxStowResponseBytes + 1);
        if (responseBytes.length > maxStowResponseBytes) {
            throw new IOException("Target STOW-RS response exceeds " + maxStowResponseBytes + " bytes");
        }
        return new String(responseBytes, charset);
    }

    public DicomStreamResponse getWadoImage(String studyUID, String seriesUID, String objectUID, String sourceId, String contentType) {
        URI uri = UriComponentsBuilder.fromUri(URI.create(targetBaseUrl))
                .path("/hime-server/dcm/wado")
                .queryParam("requestType", "WADO")
                .queryParam("studyUID", studyUID)
                .queryParam("seriesUID", seriesUID)
                .queryParam("objectUID", objectUID)
                .queryParam("contentType", contentType)
                .queryParamIfPresent("SourceID", Optional.ofNullable(sourceId))
                .build().encode().toUri();
        return openStreamingResponse(uri, MediaType.parseMediaType(contentType));
    }

    public DicomStreamResponse downloadStudyZip(String studyUID, String patientId) {
        URI uri = UriComponentsBuilder.fromUri(URI.create(targetBaseUrl))
                .path("/hime-server/dcm/studies/{studyUID}/zip")
                .queryParam("PatientID", patientId)
                .buildAndExpand(studyUID).encode().toUri();
        return openStreamingResponse(uri, MediaType.parseMediaType("application/zip"));
    }

    private DicomStreamResponse openStreamingResponse(URI uri, MediaType fallbackContentType) {
        HttpRequest request = HttpRequest.newBuilder(uri)
                .header("Accept", fallbackContentType.toString())
                .GET()
                .build();
        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            MediaType contentType = response.headers().firstValue("Content-Type")
                    .map(value -> {
                        try {
                            return MediaType.parseMediaType(value);
                        } catch (Exception ignored) {
                            return fallbackContentType;
                        }
                    })
                    .orElse(fallbackContentType);
            long contentLength = response.headers().firstValueAsLong("Content-Length").orElse(-1L);
            return new DicomStreamResponse(response.statusCode(), contentType, contentLength, response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DICOM 응답 스트리밍 대기 중 인터럽트 발생", e);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "DICOM target 스트림 연결 실패", e);
        }
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
