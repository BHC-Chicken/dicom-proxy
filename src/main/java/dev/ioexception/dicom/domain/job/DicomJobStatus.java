package dev.ioexception.dicom.domain.job;

public enum DicomJobStatus {
	SUBMITTED,
	PROCESSING,
	COMPLETED,
	PARTIAL_SUCCESS,
	FAILED;

	public boolean isTerminal() {
		return this == COMPLETED || this == PARTIAL_SUCCESS || this == FAILED;
	}
}
