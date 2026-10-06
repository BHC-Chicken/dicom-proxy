package dev.ioexception.dicom.service.dicom;

import dev.ioexception.dicom.common.DicomDatPackerUtil;
import dev.ioexception.dicom.common.DicomMultipartParserUtil;
import dev.ioexception.dicom.domain.job.DicomJob;
import dev.ioexception.dicom.dto.dicom.response.DicomForwardResponse;
import dev.ioexception.dicom.repository.job.DicomJobRepository;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import co.elastic.apm.api.ElasticApm;
import co.elastic.apm.api.Scope;
import co.elastic.apm.api.Transaction;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@Slf4j
@Service
@RequiredArgsConstructor
public class DicomJobService {

	private final DicomJobRepository dicomJobRepository;
	private final DicomWebService dicomWebService;

	public record SpoolTask(Path spoolFile, String boundary, String fileName) {}

	public DicomJob submitJob(String sourceId, List<MultipartFile> files, HttpServletRequest request) {
		if (files != null && !files.isEmpty()) {
			List<MultipartFile> validFiles = files.stream()
					.filter(f -> f != null && !f.isEmpty())
					.toList();

			if (!validFiles.isEmpty()) {
				return submitDatFilesJob(sourceId, validFiles);
			}
		}

		return submitDirectStreamJob(sourceId, request);
	}

	public Optional<DicomJob> getJob(String jobId) {
		return dicomJobRepository.findById(jobId);
	}

	private DicomJob submitDatFilesJob(String sourceId, List<MultipartFile> files) {
		log.info("[JobService] 다중 .dat 파일 비동기 작업 접수 시작 (총 {}개 파일), SourceID: {}", files.size(), sourceId);
		List<SpoolTask> tasks = new ArrayList<>();
		List<Path> createdSpoolFiles = new ArrayList<>();

		try {
			for (MultipartFile file : files) {
				String fileName = (file.getOriginalFilename() != null && !file.getOriginalFilename().isBlank())
						? file.getOriginalFilename()
						: "unknown";
				String boundary = DicomMultipartParserUtil.extractBoundary(file.getContentType());
				if (boundary == null || boundary.isBlank()) {
					boundary = DicomDatPackerUtil.DEFAULT_BOUNDARY;
				}

				Path spoolFile = dicomWebService.prepareSpoolFile(file);
				createdSpoolFiles.add(spoolFile);
				tasks.add(new SpoolTask(spoolFile, boundary, fileName));
			}
		} catch (Exception e) {
			log.error("[JobService] Job 접수 단계에서 스풀 파일 생성 실패 - 생성된 임시 파일 전체 정리", e);
			for (Path path : createdSpoolFiles) {
				dicomWebService.deleteSpoolFile(path);
			}
			if (e instanceof ResponseStatusException rse) {
				throw rse;
			}
			throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "스풀 파일 생성 실패: " + e.getMessage(), e);
		}

		String jobId = UUID.randomUUID().toString();
		DicomJob job = new DicomJob(jobId, sourceId, tasks.size());
		dicomJobRepository.save(job);
		log.info("[JobService] Job 생성 완료 (JobId: {}, 파일 수: {}) - VirtualThread 비동기 작업 위임", jobId, tasks.size());

		String traceParent = captureCurrentTraceParent();
		executeJobAsync(jobId, traceParent, () -> runMultipleFilesJob(job, tasks, sourceId));

		return job;
	}

	private DicomJob submitDirectStreamJob(String sourceId, HttpServletRequest request) {
		String contentType = request.getContentType();
		log.info("[JobService] DICOM 다이렉트 스트림 작업 접수 - Content-Type: {}, SourceID: {}", contentType, sourceId);

		String boundary = DicomMultipartParserUtil.extractBoundary(contentType);
		if (boundary == null || boundary.isBlank()) {
			boundary = DicomDatPackerUtil.DEFAULT_BOUNDARY;
		}

		Path spoolFile = null;
		try {
			dicomWebService.checkFreeDiskSpace();
			try (InputStream input = request.getInputStream()) {
				spoolFile = dicomWebService.spoolWithPermit(input);
			}
		} catch (Exception e) {
			if (spoolFile != null) {
				dicomWebService.deleteSpoolFile(spoolFile);
			}
			log.error("[JobService] 다이렉트 스트림 스풀링 중 오류 발생", e);
			if (e instanceof ResponseStatusException rse) {
				throw rse;
			}
			throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "DICOM 요청 스풀링 실패: " + e.getMessage(), e);
		}

		String jobId = UUID.randomUUID().toString();
		DicomJob job = new DicomJob(jobId, sourceId, 1);
		dicomJobRepository.save(job);

		final Path finalSpoolFile = spoolFile;
		final String finalBoundary = boundary;
		log.info("[JobService] 다이렉트 스트림 Job 생성 완료 (JobId: {}) - VirtualThread 비동기 작업 위임", jobId);

		String traceParent = captureCurrentTraceParent();
		executeJobAsync(jobId, traceParent, () -> runSingleStreamJob(job, finalSpoolFile, finalBoundary, sourceId));

		return job;
	}

	private String captureCurrentTraceParent() {
		try {
			Transaction currentTx = ElasticApm.currentTransaction();
			if (currentTx != null) {
				Map<String, String> headers = new HashMap<>();
				currentTx.injectTraceHeaders(headers::put);
				String traceparent = headers.get("traceparent");
				if (traceparent != null && !traceparent.isBlank()) {
					return traceparent;
				}
			}
		} catch (Throwable t) {
			log.debug("[JobService] APM traceparent 추출 건너뜀: {}", t.getMessage());
		}

		String mdcTraceId = MDC.get("trace.id");
		if (mdcTraceId != null && mdcTraceId.length() == 32) {
			String fakeSpanId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
			return "00-" + mdcTraceId + "-" + fakeSpanId + "-01";
		}
		return null;
	}

	private void executeJobAsync(String jobId, String traceParent, Runnable task) {
		Thread.ofVirtual().name("dicom-job-" + jobId).start(() -> {
			Transaction backgroundTx = null;
			Scope scope = null;
			try {
				if (traceParent != null) {
					backgroundTx = ElasticApm.startTransactionWithRemoteParent(name -> {
						if ("traceparent".equalsIgnoreCase(name)) {
							return traceParent;
						}
						return null;
					});
				} else {
					backgroundTx = ElasticApm.startTransaction();
				}

				if (backgroundTx != null) {
					backgroundTx.setName("DicomJob: " + jobId);
					backgroundTx.setType("job");
					scope = backgroundTx.activate();
					String traceId = backgroundTx.getTraceId();
					if (traceId != null && !traceId.isBlank()) {
						MDC.put("trace.id", traceId);
					}
				}
			} catch (Throwable t) {
				log.debug("[JobService] 백그라운드 APM 트랜잭션 시작 건너뜀: {}", t.getMessage());
			}

			try {
				task.run();
			} catch (Throwable t) {
				if (backgroundTx != null) {
					try {
						backgroundTx.captureException(t);
					} catch (Throwable ignored) {}
				}
				throw t;
			} finally {
				if (scope != null) {
					try {
						scope.close();
					} catch (Throwable ignored) {}
				}
				if (backgroundTx != null) {
					try {
						backgroundTx.end();
					} catch (Throwable ignored) {}
				}
				MDC.remove("trace.id");
			}
		});
	}

	private void runMultipleFilesJob(DicomJob job, List<SpoolTask> tasks, String sourceId) {
		job.markProcessing();
		dicomJobRepository.save(job);
		log.info("[JobService] Job({}) 백그라운드 처리 시작 (상태: PROCESSING)", job.getJobId());

		String parentTraceId = MDC.get("trace.id");
		Set<Path> cleanedFiles = ConcurrentHashMap.newKeySet();
		try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<Void>> futures = tasks.stream()
					.map(task -> CompletableFuture.runAsync(() -> {
						if (parentTraceId != null && !parentTraceId.isBlank()) {
							MDC.put("trace.id", parentTraceId);
						}
						try {
							List<DicomForwardResponse> responses = dicomWebService.validateAndForwardSpool(
									sourceId, task.spoolFile(), task.boundary(), task.fileName());
							job.addResults(responses);
						} catch (Exception e) {
							String errorMessage = (e instanceof ResponseStatusException rse && rse.getReason() != null)
									? rse.getReason()
									: (e.getMessage() != null && !e.getMessage().isBlank() ? e.getMessage() : e.getClass().getSimpleName());
							log.error("[JobService] Job({}) 파일({}) 중계 처리 실패: {}", job.getJobId(), task.fileName(), errorMessage, e);
							job.addResult(new DicomForwardResponse(
									"UNKNOWN", null, null,
									List.of(String.format("[%s] 실패 -> %s", task.fileName(), errorMessage))));
						} finally {
							if (cleanedFiles.add(task.spoolFile())) {
								dicomWebService.deleteSpoolFile(task.spoolFile());
							}
							if (parentTraceId != null) {
								MDC.remove("trace.id");
							}
						}
					}, executor))
					.toList();

			CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
			job.completeWithFinalStatus();
			log.info("[JobService] Job({}) 백그라운드 처리 완료 - 최종 상태: {}, 성공: {}, 실패: {}",
					job.getJobId(), job.getStatus(), job.getSuccessCount(), job.getFailureCount());
		} catch (Throwable t) {
			log.error("[JobService] Job({}) 백그라운드 실행 중 치명적 예외 발생", job.getJobId(), t);
			job.fail("작업 비정상 종료: " + t.getMessage());
		} finally {
			for (SpoolTask task : tasks) {
				if (cleanedFiles.add(task.spoolFile())) {
					dicomWebService.deleteSpoolFile(task.spoolFile());
				}
			}
			dicomJobRepository.save(job);
		}
	}

	private void runSingleStreamJob(DicomJob job, Path spoolFile, String boundary, String sourceId) {
		job.markProcessing();
		dicomJobRepository.save(job);
		log.info("[JobService] Job({}) 다이렉트 스트림 백그라운드 처리 시작 (상태: PROCESSING)", job.getJobId());

		try {
			List<DicomForwardResponse> responses = dicomWebService.validateAndForwardSpool(
					sourceId, spoolFile, boundary, "direct-request");
			job.addResults(responses);
			job.completeWithFinalStatus();
			log.info("[JobService] Job({}) 다이렉트 스트림 처리 완료 - 최종 상태: {}", job.getJobId(), job.getStatus());
		} catch (Throwable t) {
			String errorMessage = (t instanceof ResponseStatusException rse && rse.getReason() != null)
					? rse.getReason()
					: (t.getMessage() != null && !t.getMessage().isBlank() ? t.getMessage() : t.getClass().getSimpleName());
			log.error("[JobService] Job({}) 다이렉트 스트림 처리 실패: {}", job.getJobId(), errorMessage, t);
			job.addResult(new DicomForwardResponse(
					"UNKNOWN", null, null,
					List.of(String.format("[direct-request] 실패 -> %s", errorMessage))));
			job.completeWithFinalStatus();
		} finally {
			dicomWebService.deleteSpoolFile(spoolFile);
			dicomJobRepository.save(job);
		}
	}
}
