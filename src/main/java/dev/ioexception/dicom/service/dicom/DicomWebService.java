package dev.ioexception.dicom.service.dicom;

import co.elastic.apm.api.CaptureSpan;
import dev.ioexception.dicom.common.DicomMultipartParserUtil;
import dev.ioexception.dicom.common.DicomXmlParserUtil;
import dev.ioexception.dicom.config.dicom.DicomDcmClient;
import dev.ioexception.dicom.dto.MetadataFormat;
import dev.ioexception.dicom.dto.ValidatedDicomPayload;
import dev.ioexception.dicom.dto.dicom.response.DicomForwardResponse;
import dev.ioexception.dicom.dto.dicom.response.DicomStreamResponse;
import dev.ioexception.dicom.dto.dicom.response.DicomUidResponse;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.util.unit.DataSize;
import org.springframework.web.client.RestClient;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Semaphore;
import java.util.stream.Stream;

@Slf4j
@Service
public class DicomWebService {

	private static final int STREAM_BUFFER_BYTES = 128 * 1024;
	private static final int MAX_FORWARD_RETRIES = 3;
	private static final long INITIAL_RETRY_BACKOFF_MS = 1000L;

	private final DicomDcmClient dicomDcmClient;
	private final RestClient restClient;
	private final HttpClient httpClient;
	private final Semaphore forwardSemaphore;
	private final Semaphore spoolSemaphore;
	private final String targetBaseUrl;
	private final int maxPermits;
	private final Path spoolDirectory;
	private final long maxSpoolBytes;
	private final long minFreeDiskBytes;
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
			@Value("${dicom.forward.min-free-disk-space:5GB}") DataSize minFreeDiskSpace,
			@Value("${dicom.forward.max-metadata-scan-size:8MB}") DataSize maxMetadataScanSize,
			@Value("${dicom.forward.max-stow-response-size:1MB}") DataSize maxStowResponseSize,
			@Value("${dicom.forward.max-concurrent-spools:${DICOM_FORWARD_MAX_CONCURRENT_SPOOLS:10}}") int maxConcurrentSpools,
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
		this.minFreeDiskBytes = minFreeDiskSpace.toBytes();
		this.maxMetadataScanBytes = maxMetadataScanSize.toBytes();
		this.maxStowResponseBytes = (int) maxStowResponseSize.toBytes();
		this.staleSpoolRetention = staleSpoolRetention;
		this.forwardSemaphore = new Semaphore(maxPermits);
		this.spoolSemaphore = new Semaphore(maxConcurrentSpools);
		log.info("[DicomWebService] 초기화 완료 - Target: {}, Permits: {}, SpoolDir: {}, MaxSpool: {} bytes, MinFreeDisk: {} bytes",
				targetBaseUrl, maxPermits, this.spoolDirectory, this.maxSpoolBytes, this.minFreeDiskBytes);
	}

	@PostConstruct
	void cleanupStaleSpoolFiles() {
		try {
			Files.createDirectories(spoolDirectory);
			Instant cutoff = Instant.now().minus(staleSpoolRetention);
			try (Stream<Path> files = Files.list(spoolDirectory)) {
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


	void checkFreeDiskSpace() throws IOException {
		Files.createDirectories(spoolDirectory);
		FileStore store = Files.getFileStore(spoolDirectory);
		long usableSpace = store.getUsableSpace();
		if (usableSpace < minFreeDiskBytes) {
			long usableMb = usableSpace / (1024 * 1024);
			long requiredMb = minFreeDiskBytes / (1024 * 1024);
			log.error("[Proxy] 디스크 여유 공간 부족 (현재: {}MB, 최소 요구치: {}MB)", usableMb, requiredMb);
			throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
					String.format("서버 디스크 여유 공간 부족 (남은 용량: %dMB, 필요 용량: %dMB)", usableMb, requiredMb));
		}
	}

	Path prepareSpoolFile(MultipartFile file) throws IOException {
		checkFreeDiskSpace();
		Files.createDirectories(spoolDirectory);
		Path spoolFile = Files.createTempFile(spoolDirectory, "dicom-forward-", ".part");
		try {
			file.transferTo(spoolFile);
			long fileSize = Files.size(spoolFile);
			if (fileSize > maxSpoolBytes) {
				throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
						"DICOM payload가 spool 제한을 초과했습니다.");
			}
			return spoolFile;
		} catch (IOException | RuntimeException e) {
			deleteSpoolFile(spoolFile);
			throw e;
		}
	}


	Path spoolWithPermit(InputStream input) throws IOException {
		acquireSpoolPermit();
		try {
			return spoolPayload(input);
		} finally {
			spoolSemaphore.release();
			log.debug("[SpoolSemaphore] 스풀링 완료 후 반납 (현재 이용가능 Permits: {})", spoolSemaphore.availablePermits());
		}
	}

	private void acquireSpoolPermit() {
		try {
			spoolSemaphore.acquire();
			log.debug("[SpoolSemaphore] 스풀링 허가 획득 (남은 Permits: {})", spoolSemaphore.availablePermits());
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR,
					"DICOM spool 대기 중 인터럽트 발생", e);
		}
	}

	@CaptureSpan(value = "STOW-RS Spool Validation", type = "proxy", subtype = "dicom")
	List<DicomForwardResponse> validateAndForwardSpool(
			String sourceId, Path spoolFile, String boundary, String sourceName) throws IOException {
		ValidatedDicomPayload payload = DicomMultipartParserUtil.validateSingleStudy(
				spoolFile, boundary, maxMetadataScanBytes);
		DicomUidResponse uid = payload.representativeUid();
		String contentType = "multipart/related; type=\"application/dicom\"; boundary=" + payload.detectedBoundary();

		log.info("[Proxy] [{}] 검증 완료 후 bounded-memory streaming 시작 (Boundary: {}, Part 수: {}, StudyUID: {})",
				sourceName, payload.detectedBoundary(), payload.partCount(), uid.studyUid());

		String forwardStatus = forwardStreamWithRetry(
				spoolFile, Files.size(spoolFile), contentType, uid.studyUid(), sourceId);

		return List.of(new DicomForwardResponse(
				uid.studyUid(), uid.seriesUid(), uid.sopInstanceUid(),
				List.of(String.format("[StudyUID: %s] %s", uid.studyUid(), forwardStatus))));
	}

	private Path spoolPayload(InputStream input) throws IOException {
		Files.createDirectories(spoolDirectory);
		Path spoolFile = Files.createTempFile(spoolDirectory, "dicom-forward-", ".part");
		long total = 0;
		byte[] buffer = new byte[STREAM_BUFFER_BYTES];
		try (OutputStream output = Files.newOutputStream(spoolFile)) {
			int read;
			while ((read = input.read(buffer)) != -1) {
				if (Thread.currentThread().isInterrupted()) {
					throw new IOException("디스크 스풀링 중 인터럽트가 발생하여 취소되었습니다.");
				}
				total += read;
				if (total > maxSpoolBytes) {
					throw new ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
							"DICOM payload가 spool 제한을 초과했습니다.");
				}
				output.write(buffer, 0, read);
			}
			output.flush();
			return spoolFile;
		} catch (IOException | RuntimeException e) {
			Files.deleteIfExists(spoolFile);
			throw e;
		}
	}

	void deleteSpoolFile(Path spoolFile) {
		if (spoolFile == null) {
			return;
		}
		try {
			Files.deleteIfExists(spoolFile);
		} catch (IOException e) {
			log.warn("[Proxy] spool 파일 삭제 실패: {}", spoolFile, e);
		}
	}

	private String forwardStreamWithRetry(Path spoolFile, long contentLength, String contentType, String studyUid,
			String sourceId) {
		long backoffMillis = INITIAL_RETRY_BACKOFF_MS;
		String lastError = "알 수 없는 오류";

		for (int attempt = 1; attempt <= MAX_FORWARD_RETRIES; attempt++) {
			boolean acquired = false;
			try {
				log.debug("[Semaphore] 점유 대기 시작 (현재 이용가능 Permits: {}/{})", forwardSemaphore.availablePermits(),
						maxPermits);
				forwardSemaphore.acquire();
				acquired = true;
				log.info("[Semaphore] 허가 획득 완료 (시도 {}/{}, 남은 Permits: {}/{}) - StudyUID: {}",
						attempt, MAX_FORWARD_RETRIES, forwardSemaphore.availablePermits(), maxPermits, studyUid);

				try (InputStream stream = Files.newInputStream(spoolFile)) {
					return streamToTargetServer(stream, contentLength, contentType, studyUid, sourceId);
				}
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				log.error("[Semaphore] 전송 대기 중 스레드 중단 (StudyUID: {})", studyUid, e);
				throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "DICOM 전송 대기 중 인터럽트 발생");
			} catch (NonRetryableForwardException e) {
				log.error("[RestClient] [StudyUID: {}] 재시도 불가 오류 발생: {}", studyUid, e.getMessage());
				return String.format("실패 -> %s", e.getMessage());
			} catch (Exception e) {
				lastError = e.getMessage();
				log.warn("[RestClient] [StudyUID: {}] 전송 실패 ({}/{}): {}", studyUid, attempt, MAX_FORWARD_RETRIES,
						e.getMessage());
				if (attempt == MAX_FORWARD_RETRIES) {
					break;
				}
			} finally {
				if (acquired) {
					forwardSemaphore.release();
					log.debug("[Semaphore] 반납 완료 (현재 이용가능 Permits: {}/{}) - StudyUID: {}",
							forwardSemaphore.availablePermits(), maxPermits, studyUid);
				}
			}

			try {
				log.info("[Retry] {}ms 대기 후 재전송 시도 (StudyUID: {})", backoffMillis, studyUid);
				Thread.sleep(backoffMillis);
				backoffMillis *= 2;
			} catch (InterruptedException ie) {
				Thread.currentThread().interrupt();
				throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "재시도 대기 중 인터럽트 발생", ie);
			}
		}

		return String.format("실패 -> 최대 재시도(%d회) 초과: %s", MAX_FORWARD_RETRIES, lastError);
	}

	private static class NonRetryableForwardException extends RuntimeException {
		public NonRetryableForwardException(String message) {
			super(message);
		}
	}

	@CaptureSpan(value = "STOW-RS Target Forwarding", type = "ext", subtype = "http")
	private String streamToTargetServer(InputStream combinedStream, long contentLength, String contentType,
			String studyUid, String sourceId) throws IOException {
		log.info("[RestClient] 파일 기반 스트림 전송 중... -> Target: {}/hime-server/dcm/studies/{}, Content-Type: {}",
				targetBaseUrl, studyUid, contentType);

		String responseBody = restClient.post()
				.uri(targetBaseUrl + "/hime-server/dcm/studies/{studyUid}?SourceID={sourceId}", studyUid, sourceId)
				.contentType(MediaType.parseMediaType(contentType))
				.contentLength(contentLength)
				.accept(MediaType.parseMediaType("application/dicom+xml"))
				.body(outputStream -> transferWithBuffer(combinedStream, outputStream, STREAM_BUFFER_BYTES))
				.exchange((request, response) -> {
					int statusCode = response.getStatusCode().value();
					if (!response.getStatusCode().is2xxSuccessful()) {
						String errorMessage = "Target STOW-RS returned HTTP " + statusCode;
						if (statusCode >= 400 && statusCode < 500 && statusCode != 408 && statusCode != 429) {
							throw new NonRetryableForwardException(errorMessage);
						}
						throw new IOException(errorMessage);
					}
					Charset charset = Optional.ofNullable(response.getHeaders().getContentType())
							.map(MediaType::getCharset)
							.orElse(StandardCharsets.UTF_8);
					return readBoundedStowResponse(response.getBody(), charset);
				});

		String parsedResult = DicomXmlParserUtil.extractSuccessInfo(responseBody);
		log.info("[RestClient] [StudyUID: {}] STOW-RS Target 서버 전송 성공: {}", studyUid, parsedResult);
		return String.format("성공 -> %s", parsedResult);
	}

	private void transferWithBuffer(InputStream in, OutputStream out, int bufferSize) throws IOException {
		byte[] buffer = new byte[bufferSize];
		int read;
		while ((read = in.read(buffer)) != -1) {
			if (Thread.currentThread().isInterrupted()) {
				throw new IOException("스트리밍 전송 중 인터럽트가 발생하여 취소되었습니다.");
			}
			out.write(buffer, 0, read);
		}
		out.flush();
	}

	private String readBoundedStowResponse(InputStream input, Charset charset) throws IOException {
		byte[] responseBytes = input.readNBytes(maxStowResponseBytes + 1);
		if (responseBytes.length > maxStowResponseBytes) {
			throw new IOException("Target STOW-RS response exceeds " + maxStowResponseBytes + " bytes");
		}
		return new String(responseBytes, charset);
	}

	public DicomStreamResponse getWadoImage(String studyUID, String seriesUID, String objectUID, String sourceId,
			String contentType) {
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

	public String retrieveStudyMetadata(String studyUID, String patientId, MetadataFormat format, String includePrivate,
			String groups, String xsl) {
		return dicomDcmClient.retrieveStudyMetadata(studyUID, format.getAcceptHeader(), patientId, includePrivate,
				groups, xsl);
	}

	public void createDicomManifestKOS(String studyUid, String kosUid, String sourceId, boolean hasReport,
			Integer totalInstanceCount) {
		String hasReportStr = hasReport ? "true" : "false";
		String totalCountStr = totalInstanceCount != null ? String.valueOf(totalInstanceCount) : null;
		dicomDcmClient.createDicomManifestKOS(studyUid, kosUid, sourceId, hasReportStr, totalCountStr);
	}
}
