package dev.ioexception.dicom.domain.job;

import dev.ioexception.dicom.dto.dicom.response.DicomForwardResponse;
import lombok.Getter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Getter
public class DicomJob {
	private final String jobId;
	private final String sourceId;
	private final int totalFiles;
	private final Instant createdAt;

	private volatile DicomJobStatus status;
	private volatile int processedFiles;
	private volatile int successCount;
	private volatile int failureCount;
	private volatile String errorMessage;
	private volatile Instant startedAt;
	private volatile Instant completedAt;

	private final List<DicomForwardResponse> results = Collections.synchronizedList(new ArrayList<>());

	public DicomJob(String jobId, String sourceId, int totalFiles) {
		this.jobId = jobId;
		this.sourceId = sourceId;
		this.totalFiles = totalFiles;
		this.status = DicomJobStatus.SUBMITTED;
		this.createdAt = Instant.now();
	}

	public synchronized void markProcessing() {
		if (this.status == DicomJobStatus.SUBMITTED) {
			this.status = DicomJobStatus.PROCESSING;
			this.startedAt = Instant.now();
		}
	}

	public synchronized void addResult(DicomForwardResponse response) {
		if (response != null) {
			this.results.add(response);
			this.processedFiles++;
			if (response.isSuccess()) {
				this.successCount++;
			} else {
				this.failureCount++;
			}
		}
	}

	public synchronized void addResults(List<DicomForwardResponse> responses) {
		if (responses != null) {
			for (DicomForwardResponse response : responses) {
				addResult(response);
			}
		}
	}

	public synchronized void completeWithFinalStatus() {
		this.completedAt = Instant.now();
		if (this.totalFiles == 0) {
			this.status = DicomJobStatus.COMPLETED;
		} else if (this.successCount == this.totalFiles) {
			this.status = DicomJobStatus.COMPLETED;
		} else if (this.successCount == 0) {
			this.status = DicomJobStatus.FAILED;
			if (this.errorMessage == null || this.errorMessage.isBlank()) {
				this.errorMessage = "모든 파일의 포워딩 작업이 실패했습니다.";
			}
		} else {
			this.status = DicomJobStatus.PARTIAL_SUCCESS;
		}
	}

	public synchronized void fail(String errorMessage) {
		this.status = DicomJobStatus.FAILED;
		this.errorMessage = errorMessage;
		this.completedAt = Instant.now();
	}

	public List<DicomForwardResponse> getResults() {
		synchronized (results) {
			return new ArrayList<>(results);
		}
	}
}
