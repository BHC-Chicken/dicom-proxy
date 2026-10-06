package dev.ioexception.dicom.dto.dicom.response;

import dev.ioexception.dicom.domain.job.DicomJob;
import dev.ioexception.dicom.domain.job.DicomJobStatus;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.Instant;
import java.util.List;

@Schema(description = "DICOM 비동기 중계 작업 상태 응답")
public record DicomJobResponse(
		@Schema(description = "작업 고유 식별자 (UUID)", example = "3fa85f64-5717-4562-b3fc-2c963f66afa6")
		String jobId,

		@Schema(description = "요청 출처 OID", example = "1.2.410.100110.10.99999981")
		String sourceId,

		@Schema(description = "작업 상태 (SUBMITTED, PROCESSING, COMPLETED, PARTIAL_SUCCESS, FAILED)", example = "PROCESSING")
		DicomJobStatus status,

		@Schema(description = "전체 파일 수", example = "2")
		int totalFiles,

		@Schema(description = "처리 완료된 파일 수", example = "1")
		int processedFiles,

		@Schema(description = "성공 파일 수", example = "1")
		int successCount,

		@Schema(description = "실패 파일 수", example = "0")
		int failureCount,

		@Schema(description = "전송 상세 결과 목록")
		List<DicomForwardResponse> results,

		@Schema(description = "작업 전체 실패 시 에러 메시지", example = "null")
		String errorMessage,

		@Schema(description = "작업 접수 시각")
		Instant createdAt,

		@Schema(description = "작업 시작 시각")
		Instant startedAt,

		@Schema(description = "작업 완료 시각")
		Instant completedAt
) {
	public static DicomJobResponse from(DicomJob job) {
		if (job == null) {
			return null;
		}
		return new DicomJobResponse(
				job.getJobId(),
				job.getSourceId(),
				job.getStatus(),
				job.getTotalFiles(),
				job.getProcessedFiles(),
				job.getSuccessCount(),
				job.getFailureCount(),
				job.getResults(),
				job.getErrorMessage(),
				job.getCreatedAt(),
				job.getStartedAt(),
				job.getCompletedAt()
		);
	}
}
