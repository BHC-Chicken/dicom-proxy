package dev.ioexception.dicom.repository.job;

import dev.ioexception.dicom.domain.job.DicomJob;

import java.util.Optional;

public interface DicomJobRepository {
	void save(DicomJob job);

	Optional<DicomJob> findById(String jobId);

	void delete(String jobId);
}
