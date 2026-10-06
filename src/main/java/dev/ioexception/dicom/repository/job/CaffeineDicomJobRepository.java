package dev.ioexception.dicom.repository.job;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.ioexception.dicom.domain.job.DicomJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
public class CaffeineDicomJobRepository implements DicomJobRepository {

	private final Cache<String, DicomJob> cache;

	public CaffeineDicomJobRepository(
			@Value("${dicom.forward.job.retention:24h}") Duration retention,
			@Value("${dicom.forward.job.max-entries:10000}") long maxEntries) {
		this.cache = Caffeine.newBuilder()
				.expireAfterWrite(retention)
				.maximumSize(maxEntries)
				.recordStats()
				.build();
		log.info("[JobRepository] Caffeine Job 저장소 초기화 (보관 기간: {}, 최대 엔트리: {})", retention, maxEntries);
	}

	@Override
	public void save(DicomJob job) {
		if (job != null && job.getJobId() != null) {
			cache.put(job.getJobId(), job);
		}
	}

	@Override
	public Optional<DicomJob> findById(String jobId) {
		if (jobId == null || jobId.isBlank()) {
			return Optional.empty();
		}
		return Optional.ofNullable(cache.getIfPresent(jobId));
	}

	@Override
	public void delete(String jobId) {
		if (jobId != null) {
			cache.invalidate(jobId);
		}
	}
}
