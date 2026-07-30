package dev.ioexception.dicom.service;

import co.elastic.apm.api.ElasticApm;
import dev.ioexception.dicom.dto.request.PurgeRequest;
import dev.ioexception.dicom.dto.response.PurgeSummaryInfoResponse;
import dev.ioexception.dicom.entity.postgresql.Instance;
import dev.ioexception.dicom.entity.postgresql.Study;
import dev.ioexception.dicom.event.DicomErrorEvent;
import dev.ioexception.dicom.exception.dicom.DicomErrorCode;
import dev.ioexception.dicom.exception.dicom.DicomErrorException;
import dev.ioexception.dicom.repository.postgresql.InstanceRepository;
import dev.ioexception.dicom.repository.postgresql.StudyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

@Slf4j
@Service
@ConditionalOnProperty(name = "dicom.purge.enabled", havingValue = "true", matchIfMissing = false)
public class DicomPurgeService {
	private final StudyRepository studyRepository;
	private final InstanceRepository instanceRepository;
	private final DicomPurgeExecutor dicomPurgeExecutor;
	private final ApplicationEventPublisher eventPublisher;

	// 동시 실행 한도 제어 (애플리케이션 전역 Semaphore)
	private final Semaphore semaphore;
	private final int maxPurgePermits;

	public DicomPurgeService(
			StudyRepository studyRepository,
			InstanceRepository instanceRepository,
			DicomPurgeExecutor dicomPurgeExecutor,
			ApplicationEventPublisher eventPublisher,
			@Value("${dicom.purge.max-threads:${PURGE_MAX_THREADS:5}}") int maxPurgePermits) {
		this.studyRepository = studyRepository;
		this.instanceRepository = instanceRepository;
		this.dicomPurgeExecutor = dicomPurgeExecutor;
		this.eventPublisher = eventPublisher;
		this.maxPurgePermits = maxPurgePermits;
		this.semaphore = new Semaphore(maxPurgePermits);
	}

	private record PurgeResult(boolean isSuccess, Integer studyKey) {
	}

	/**
	 * 지정된 조건(기간, 옵션)에 따라 DICOM 퍼지 배치 프로세스를 Orchestrate합니다.
	 * Java 가상 스레드와 전역 Semaphore를 활용하여 지정된 개수만큼 동시 병렬 처리합니다.
	 */
	public PurgeSummaryInfoResponse executePurgeProcess(PurgeRequest request) {
		List<Study> studyList = getStudyListForPurge(request);
		log.info("총 {} 건의 검사(Study) 데이터를 비동기 병렬 처리합니다. (동시 처리 제한: {}개)", studyList.size(), maxPurgePermits);

		// 가상 스레드 풀을 이용하여 개별 Study 별로 퍼지 프로세스 조율
		try (ExecutorService virtualExecutor = Executors.newVirtualThreadPerTaskExecutor()) {
			List<CompletableFuture<PurgeResult>> futures = studyList.stream()
					.map(study -> CompletableFuture.supplyAsync(() -> purgeSingleStudy(study, request),
							virtualExecutor))
					.toList();

			// 모든 비동기 작업이 끝날 때까지 동기 대기 및 결과 취합
			List<PurgeResult> results = futures.stream()
					.map(CompletableFuture::join)
					.toList();

			return aggregatePurgeResults(results, studyList.size());
		}
	}

	/**
	 * 기간 검색을 통해 삭제 대상인 Study 목록을 조회합니다.
	 */
	private List<Study> getStudyListForPurge(PurgeRequest request) {
		LocalDateTime start = request.startTime() != null ? request.startTime().atStartOfDay() : null;
		LocalDateTime end = request.endTime() != null ? request.endTime().plusDays(1).atStartOfDay() : null;
		log.info("DICOM Purge 프로세스 시작 (조회 기간: {} ~ {})", start, end);

		List<Study> studyList = studyRepository
				.findByCreatedDttmGreaterThanEqualAndCreatedDttmLessThanOrderByCreatedDttm(start, end);

		if (studyList.isEmpty()) {
			log.info("조회 기간 내에 해당하는 Study 목록이 존재하지 않습니다. (기간: {} ~ {})", start, end);

			throw new DicomErrorException(DicomErrorCode.NOT_FOUND_STUDY_LIST);
		}

		return studyList;
	}

	/**
	 * 단일 Study에 대해 세마포어 자원 획득 제어 하에 삭제 처리를 실행하고 결과를 반환합니다.
	 */
	private PurgeResult purgeSingleStudy(Study study, PurgeRequest request) {
		Integer studyKey = study.getDcmStudyKey();

		try {
			semaphore.acquire();
			log.info("검사 퍼지 처리 시작 [StudyKey: {}, StudyUID: {}]", studyKey, study.getStudyInstanceUid());

			processPurgeSteps(study, request);

			log.info("검사 퍼지 처리 성공 [StudyKey: {}]", studyKey);

			return new PurgeResult(true, studyKey);
		} catch (InterruptedException e) {
			log.error("검사 퍼지 중 대기 인터럽트 발생 [StudyKey: {}]", studyKey, e);
			Thread.currentThread().interrupt();

			return new PurgeResult(false, studyKey);
		} catch (DicomErrorException e) {
			log.error("검사 퍼지 실패 (비즈니스 오류) [StudyKey: {}, ErrorCode: {}, Message: {}]",
					studyKey, e.getErrorCode(), e.getMessage());
			String traceId = ElasticApm.currentTransaction().getTraceId();
			eventPublisher.publishEvent(new DicomErrorEvent(e, traceId));

			return new PurgeResult(false, studyKey);
		} catch (Exception e) {
			log.error("검사 퍼지 실패 (시스템 예외) [StudyKey: {}]", studyKey, e);
			String traceId = ElasticApm.currentTransaction().getTraceId();
			eventPublisher.publishEvent(new DicomErrorEvent(e, traceId));

			return new PurgeResult(false, studyKey);
		} finally {
			semaphore.release();
		}
	}

	/**
	 * 실제 검사 삭제에 필요한 비즈니스 단계(Check -> Archive -> Cleanup)를 실행합니다.
	 */
	private void processPurgeSteps(Study study, PurgeRequest request) {
		Integer studyKey = study.getDcmStudyKey();
		List<Instance> instances = null;

		if (request.check() || request.archive()) {
			instances = instanceRepository.findInstancesForPurge(studyKey);
		}

		// [1단계] 물리 파일 정합성 검증 (Check)
		if (request.check() && instances != null) {
			dicomPurgeExecutor.checkInstanceFiles(studyKey, instances, request.storageRoot());
		}

		// [2단계] ZIP 아카이브 압축 파일 생성 및 DB 등록 (Archive)
		boolean archiveSuccess = true;
		if (request.archive() && instances != null) {
			archiveSuccess = dicomPurgeExecutor.archiveStudyFiles(study, instances, request.storageRoot(),
					request.outputDir());
		}

		// [3단계] 원본 데이터 삭제 및 퍼지 큐 등록 (Cleanup)
		if (request.cleanup() && archiveSuccess) {
			dicomPurgeExecutor.cleanupStudy(studyKey);
		}
	}

	/**
	 * 병렬 처리된 전체 비동기 결과를 집계하여 최종 응답 객체를 반환합니다.
	 */
	private PurgeSummaryInfoResponse aggregatePurgeResults(List<PurgeResult> results, int totalSize) {
		int successCount = 0;
		int failureCount = 0;
		List<Integer> failedStudyKeys = new ArrayList<>();

		for (PurgeResult result : results) {
			if (result.isSuccess()) {
				successCount++;
			} else {
				failureCount++;
				failedStudyKeys.add(result.studyKey());
			}
		}

		log.info("DICOM Purge 프로세스 종료 (전체 건수: {}건, 성공: {}건, 실패: {}건, 실패 ID 목록: {})",
				totalSize, successCount, failureCount, failedStudyKeys);

		return new PurgeSummaryInfoResponse(successCount, failureCount, failedStudyKeys);
	}
}
