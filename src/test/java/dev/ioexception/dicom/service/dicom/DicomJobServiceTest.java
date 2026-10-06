package dev.ioexception.dicom.service.dicom;

import dev.ioexception.dicom.domain.job.DicomJob;
import dev.ioexception.dicom.domain.job.DicomJobStatus;
import dev.ioexception.dicom.dto.dicom.response.DicomForwardResponse;
import dev.ioexception.dicom.repository.job.CaffeineDicomJobRepository;
import dev.ioexception.dicom.repository.job.DicomJobRepository;
import dev.ioexception.dicom.service.dicom.DicomJobService;
import dev.ioexception.dicom.service.dicom.DicomWebService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DicomJobServiceTest {

    @TempDir
    Path tempDir;

    private DicomJobRepository jobRepository;
    private DicomWebService dicomWebService;
    private DicomJobService jobService;

    @BeforeEach
    void setUp() {
        jobRepository = new CaffeineDicomJobRepository(Duration.ofHours(1), 1000);
        dicomWebService = mock(DicomWebService.class);
        jobService = new DicomJobService(jobRepository, dicomWebService);
    }

    @Test
    void submitMultipleFilesSuccessfullyCompletesJobAndCleansSpoolFiles() throws IOException {
        Path spool1 = Files.createTempFile(tempDir, "test-spool-1-", ".part");
        Path spool2 = Files.createTempFile(tempDir, "test-spool-2-", ".part");

        MockMultipartFile file1 = new MockMultipartFile("files", "file1.dat", "application/octet-stream", "content1".getBytes());
        MockMultipartFile file2 = new MockMultipartFile("files", "file2.dat", "application/octet-stream", "content2".getBytes());

        when(dicomWebService.prepareSpoolFile(file1)).thenReturn(spool1);
        when(dicomWebService.prepareSpoolFile(file2)).thenReturn(spool2);

        when(dicomWebService.validateAndForwardSpool(eq("source-1"), eq(spool1), any(), eq("file1.dat")))
                .thenReturn(List.of(new DicomForwardResponse("study-1", "series-1", "sop-1", List.of("[StudyUID: 1.2.3] 성공 -> 완료"))));
        when(dicomWebService.validateAndForwardSpool(eq("source-1"), eq(spool2), any(), eq("file2.dat")))
                .thenReturn(List.of(new DicomForwardResponse("study-2", "series-2", "sop-2", List.of("[StudyUID: 1.2.3] 성공 -> 완료"))));

        DicomJob job = jobService.submitJob("source-1", List.of(file1, file2), new MockHttpServletRequest());

        assertThat(job).isNotNull();
        assertThat(job.getJobId()).isNotBlank();
        assertThat(job.getTotalFiles()).isEqualTo(2);

        await().atMost(3, TimeUnit.SECONDS).until(() -> job.getStatus() == DicomJobStatus.COMPLETED);

        assertThat(job.getSuccessCount()).isEqualTo(2);
        assertThat(job.getFailureCount()).isEqualTo(0);
        assertThat(job.getResults()).hasSize(2);

        verify(dicomWebService).deleteSpoolFile(spool1);
        verify(dicomWebService).deleteSpoolFile(spool2);
    }

    @Test
    void submitMultipleFilesWithOneFailureResultsInPartialSuccess() throws IOException {
        Path spool1 = Files.createTempFile(tempDir, "test-spool-1-", ".part");
        Path spool2 = Files.createTempFile(tempDir, "test-spool-2-", ".part");

        MockMultipartFile file1 = new MockMultipartFile("files", "good.dat", "application/octet-stream", "content1".getBytes());
        MockMultipartFile file2 = new MockMultipartFile("files", "corrupt.dat", "application/octet-stream", "content2".getBytes());

        when(dicomWebService.prepareSpoolFile(file1)).thenReturn(spool1);
        when(dicomWebService.prepareSpoolFile(file2)).thenReturn(spool2);

        when(dicomWebService.validateAndForwardSpool(eq("source-1"), eq(spool1), any(), eq("good.dat")))
                .thenReturn(List.of(new DicomForwardResponse("study-1", "series-1", "sop-1", List.of("[StudyUID: 1.2.3] 성공 -> 완료"))));
        when(dicomWebService.validateAndForwardSpool(eq("source-1"), eq(spool2), any(), eq("corrupt.dat")))
                .thenThrow(new ResponseStatusException(HttpStatus.BAD_REQUEST, "손상된 DICOM 헤더"));

        DicomJob job = jobService.submitJob("source-1", List.of(file1, file2), new MockHttpServletRequest());

        await().atMost(3, TimeUnit.SECONDS).until(() -> job.getStatus() == DicomJobStatus.PARTIAL_SUCCESS);

        assertThat(job.getSuccessCount()).isEqualTo(1);
        assertThat(job.getFailureCount()).isEqualTo(1);
        assertThat(job.getResults()).hasSize(2);

        verify(dicomWebService).deleteSpoolFile(spool1);
        verify(dicomWebService).deleteSpoolFile(spool2);
    }

    @Test
    void submitMultipleFilesFailsPhase1CleansUpCreatedSpoolFiles() throws IOException {
        Path spool1 = Files.createTempFile(tempDir, "test-spool-1-", ".part");

        MockMultipartFile file1 = new MockMultipartFile("files", "file1.dat", "application/octet-stream", "content1".getBytes());
        MockMultipartFile file2 = new MockMultipartFile("files", "file2.dat", "application/octet-stream", "content2".getBytes());

        when(dicomWebService.prepareSpoolFile(file1)).thenReturn(spool1);
        when(dicomWebService.prepareSpoolFile(file2))
                .thenThrow(new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "디스크 공간 부족"));

        assertThatThrownBy(() -> jobService.submitJob("source-1", List.of(file1, file2), new MockHttpServletRequest()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("디스크 공간 부족");

        verify(dicomWebService).deleteSpoolFile(spool1);
    }

    @Test
    void submitDirectStreamJobProcessesSuccessfully() throws IOException {
        Path spool = Files.createTempFile(tempDir, "test-direct-spool-", ".part");
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setContentType("multipart/related; boundary=boundary123");
        request.setContent("binary-payload".getBytes());

        when(dicomWebService.spoolWithPermit(any())).thenReturn(spool);
        when(dicomWebService.validateAndForwardSpool(eq("source-1"), eq(spool), eq("boundary123"), eq("direct-request")))
                .thenReturn(List.of(new DicomForwardResponse("study-direct", "series-1", "sop-1", List.of("[StudyUID: 1.2.3] 성공 -> 완료"))));

        DicomJob job = jobService.submitJob("source-1", List.of(), request);

        assertThat(job.getTotalFiles()).isEqualTo(1);

        await().atMost(3, TimeUnit.SECONDS).until(() -> job.getStatus() == DicomJobStatus.COMPLETED);

        assertThat(job.getSuccessCount()).isEqualTo(1);
        verify(dicomWebService).deleteSpoolFile(spool);
    }

    @Test
    void submitJobWithMdcTraceIdPropagatesTraceContextAndCompletes() throws IOException {
        String testTraceId = "4bf92f3577b34da6a3ce929d0e0e4736";
        org.slf4j.MDC.put("trace.id", testTraceId);

        try {
            Path spool = Files.createTempFile(tempDir, "test-mdc-spool-", ".part");
            MockMultipartFile file = new MockMultipartFile("files", "file.dat", "application/octet-stream", "data".getBytes());

            when(dicomWebService.prepareSpoolFile(file)).thenReturn(spool);
            when(dicomWebService.validateAndForwardSpool(eq("source-1"), eq(spool), any(), eq("file.dat")))
                    .thenReturn(List.of(new DicomForwardResponse("study-1", "series-1", "sop-1", List.of("[StudyUID: 1.2.3] 성공 -> 완료"))));

            DicomJob job = jobService.submitJob("source-1", List.of(file), new MockHttpServletRequest());
            assertThat(job).isNotNull();

            await().atMost(3, TimeUnit.SECONDS).until(() -> job.getStatus() == DicomJobStatus.COMPLETED);

            assertThat(job.getSuccessCount()).isEqualTo(1);
            verify(dicomWebService).deleteSpoolFile(spool);
        } finally {
            org.slf4j.MDC.remove("trace.id");
        }
    }
}
