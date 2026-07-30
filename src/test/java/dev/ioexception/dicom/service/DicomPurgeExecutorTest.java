package dev.ioexception.dicom.service;

import dev.ioexception.dicom.entity.postgresql.Instance;
import dev.ioexception.dicom.entity.postgresql.Series;
import dev.ioexception.dicom.entity.postgresql.Study;
import dev.ioexception.dicom.exception.dicom.DicomErrorException;
import dev.ioexception.dicom.repository.postgresql.StudyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipFile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

class DicomPurgeExecutorTest {

    @TempDir
    Path tempDir;

    @Test
    void failedArchiveRemovesPartFile() throws Exception {
        Path storage = Files.createDirectory(tempDir.resolve("storage"));
        Path output = Files.createDirectory(tempDir.resolve("output"));
        Files.writeString(storage.resolve("valid.dcm"), "valid", StandardCharsets.UTF_8);
        DicomPurgeExecutor executor = new DicomPurgeExecutor(mock(StudyRepository.class));

        assertThatThrownBy(() -> executor.archiveStudyFiles(
                study(), List.of(instance("valid.dcm", 1), instance("missing.dcm", 2)),
                storage.toString(), output.toString()))
                .isInstanceOf(DicomErrorException.class);

        try (var files = Files.list(output)) {
            assertThat(files.toList()).isEmpty();
        }
    }

    @Test
    void existingArchiveIsPreserved() throws Exception {
        Path storage = Files.createDirectory(tempDir.resolve("storage"));
        Path output = Files.createDirectory(tempDir.resolve("output"));
        Path existing = output.resolve("STD_1.2.3.zip");
        Files.writeString(existing, "existing", StandardCharsets.UTF_8);
        DicomPurgeExecutor executor = new DicomPurgeExecutor(mock(StudyRepository.class));

        assertThatThrownBy(() -> executor.archiveStudyFiles(
                study(), List.of(), storage.toString(), output.toString()))
                .isInstanceOf(DicomErrorException.class);

        assertThat(Files.readString(existing)).isEqualTo("existing");
    }

    @Test
    void successfulArchiveMovesVerifiedPartAndRegistersIt() throws Exception {
        Path storage = Files.createDirectory(tempDir.resolve("storage"));
        Path output = Files.createDirectory(tempDir.resolve("output"));
        Files.writeString(storage.resolve("one.dcm"), "one", StandardCharsets.UTF_8);
        StudyRepository repository = mock(StudyRepository.class);
        when(repository.createStudyArchive(eq(7), eq("STD_1.2.3.zip"), anyLong())).thenReturn(11L);
        DicomPurgeExecutor executor = new DicomPurgeExecutor(repository);

        boolean success = executor.archiveStudyFiles(
                study(), List.of(instance("one.dcm", 1)), storage.toString(), output.toString());

        Path archive = output.resolve("STD_1.2.3.zip");
        assertThat(success).isTrue();
        assertThat(archive).exists();
        try (ZipFile zip = new ZipFile(archive.toFile())) {
            assertThat(zip.size()).isEqualTo(1);
        }
        try (var files = Files.list(output)) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().endsWith(".part"))).isTrue();
        }
    }

    @Test
    void ambiguousDatabaseFailureKeepsVerifiedArchiveForReconciliation() throws Exception {
        Path storage = Files.createDirectory(tempDir.resolve("storage"));
        Path output = Files.createDirectory(tempDir.resolve("output"));
        Files.writeString(storage.resolve("one.dcm"), "one", StandardCharsets.UTF_8);
        StudyRepository repository = mock(StudyRepository.class);
        when(repository.createStudyArchive(eq(7), eq("STD_1.2.3.zip"), anyLong()))
                .thenThrow(new IllegalStateException("connection lost after procedure call"));
        DicomPurgeExecutor executor = new DicomPurgeExecutor(repository);

        assertThatThrownBy(() -> executor.archiveStudyFiles(
                study(), List.of(instance("one.dcm", 1)), storage.toString(), output.toString()))
                .isInstanceOf(DicomErrorException.class);

        assertThat(output.resolve("STD_1.2.3.zip")).exists();
        try (var files = Files.list(output)) {
            assertThat(files.noneMatch(path -> path.getFileName().toString().endsWith(".part"))).isTrue();
        }
    }

    @Test
    void atomicPublishAllowsExactlyOneWinnerWithoutReplacingTarget() throws Exception {
        Path output = Files.createDirectory(tempDir.resolve("atomic-output"));
        Path firstPart = Files.writeString(output.resolve("first.part"), "first", StandardCharsets.UTF_8);
        Path secondPart = Files.writeString(output.resolve("second.part"), "second", StandardCharsets.UTF_8);
        Path archive = output.resolve("STD_1.2.3.zip");
        DicomPurgeExecutor firstExecutor = new DicomPurgeExecutor(mock(StudyRepository.class));
        DicomPurgeExecutor secondExecutor = new DicomPurgeExecutor(mock(StudyRepository.class));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);

        try (var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = threads.submit(() -> publishAfterBarrier(firstExecutor, firstPart, archive, ready, start));
            var second = threads.submit(() -> publishAfterBarrier(secondExecutor, secondPart, archive, ready, start));
            assertThat(ready.await(5, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(first.get(5, TimeUnit.SECONDS), second.get(5, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(true, false);
        } finally {
            start.countDown();
        }

        assertThat(Files.readString(archive)).isIn("first", "second");
    }

    @Test
    void independentExecutorsForSameStudyCreateAndRegisterOnlyOnce() throws Exception {
        Path storage = Files.createDirectory(tempDir.resolve("storage"));
        Path output = Files.createDirectory(tempDir.resolve("output"));
        Files.writeString(storage.resolve("one.dcm"), "one", StandardCharsets.UTF_8);
        StudyRepository repository = mock(StudyRepository.class);
        CountDownLatch firstRegistrationStarted = new CountDownLatch(1);
        CountDownLatch allowFirstRegistrationToFinish = new CountDownLatch(1);
        when(repository.createStudyArchive(eq(7), eq("STD_1.2.3.zip"), anyLong()))
                .thenAnswer(invocation -> {
                    firstRegistrationStarted.countDown();
                    if (!allowFirstRegistrationToFinish.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("test timed out");
                    }
                    return 11L;
                });
        DicomPurgeExecutor firstPurgeExecutor = new DicomPurgeExecutor(repository);
        DicomPurgeExecutor secondPurgeExecutor = new DicomPurgeExecutor(repository);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> firstPurgeExecutor.archiveStudyFiles(
                    study(), List.of(instance("one.dcm", 1)), storage.toString(), output.toString()));
            assertThat(firstRegistrationStarted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> secondPurgeExecutor.archiveStudyFiles(
                    study(), List.of(instance("one.dcm", 1)), storage.toString(), output.toString()));

            allowFirstRegistrationToFinish.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(DicomErrorException.class);
        } finally {
            allowFirstRegistrationToFinish.countDown();
        }

        verify(repository, times(1)).createStudyArchive(eq(7), eq("STD_1.2.3.zip"), anyLong());
        assertThat(output.resolve("STD_1.2.3.zip")).exists();
    }

    private boolean publishAfterBarrier(
            DicomPurgeExecutor executor,
            Path partFile,
            Path archive,
            CountDownLatch ready,
            CountDownLatch start) throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("test timed out");
        }
        try {
            executor.publishArchiveNoReplace(partFile, archive);
            return true;
        } catch (DicomErrorException e) {
            return false;
        }
    }

    private Study study() {
        Study study = new Study();
        study.setDcmStudyKey(7);
        study.setStudyInstanceUid("1.2.3");
        return study;
    }

    private Instance instance(String relativePath, int number) {
        Series series = new Series();
        series.setSeriesNo(BigDecimal.ONE);
        Instance instance = new Instance();
        instance.setDcmInstanceKey((long) number);
        instance.setHdcmSeries(series);
        instance.setInstanceNo(BigDecimal.valueOf(number));
        instance.setSopInstanceUid("1.2.3." + number);
        instance.setLocationRoot("");
        instance.setLocationPath(relativePath);
        return instance;
    }
}
