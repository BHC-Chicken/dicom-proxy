package dev.ioexception.dicom.service;

import dev.ioexception.dicom.dto.request.PurgeRequest;
import dev.ioexception.dicom.entity.postgresql.Study;
import dev.ioexception.dicom.repository.postgresql.InstanceRepository;
import dev.ioexception.dicom.repository.postgresql.StudyRepository;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class DicomPurgeServiceTest {

	@Test
	void interruptedAcquireDoesNotReleaseAnUnownedPermit() throws Exception {
		DicomPurgeService service = serviceWithPermits(1);
		Semaphore semaphore = semaphoreOf(service);
		semaphore.acquire();

		Study study = new Study();
		study.setDcmStudyKey(1);
		study.setStudyInstanceUid("1.2.3");
		PurgeRequest request = new PurgeRequest(null, null, null, null, false, false, false);
		Method purgeSingleStudy = DicomPurgeService.class
				.getDeclaredMethod("purgeSingleStudy", Study.class, PurgeRequest.class);
		purgeSingleStudy.setAccessible(true);
		AtomicReference<Throwable> failure = new AtomicReference<>();

		Thread waitingThread = Thread.ofPlatform().start(() -> {
			try {
				purgeSingleStudy.invoke(service, study, request);
			} catch (Throwable e) {
				failure.set(e);
			}
		});

		while (waitingThread.getState() != Thread.State.WAITING) {
			Thread.onSpinWait();
		}
		waitingThread.interrupt();
		waitingThread.join(5_000);

		assertThat(waitingThread.isAlive()).isFalse();
		assertThat(failure.get()).isNull();
		assertThat(semaphore.availablePermits()).isZero();

		semaphore.release();
		assertThat(semaphore.availablePermits()).isEqualTo(1);
	}

	@Test
	void rejectsNonPositivePermitConfiguration() {
		assertThatThrownBy(() -> serviceWithPermits(0))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("at least 1");
	}

	private DicomPurgeService serviceWithPermits(int permits) {
		return new DicomPurgeService(
				mock(StudyRepository.class),
				mock(InstanceRepository.class),
				mock(DicomPurgeExecutor.class),
				mock(ApplicationEventPublisher.class),
				permits);
	}

	private Semaphore semaphoreOf(DicomPurgeService service) throws Exception {
		Field field = DicomPurgeService.class.getDeclaredField("semaphore");
		field.setAccessible(true);
		return (Semaphore) field.get(service);
	}
}
