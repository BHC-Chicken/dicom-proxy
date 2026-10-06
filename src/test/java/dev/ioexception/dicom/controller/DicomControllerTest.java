package dev.ioexception.dicom.controller;

import dev.ioexception.dicom.domain.job.DicomJob;
import dev.ioexception.dicom.domain.job.DicomJobStatus;
import dev.ioexception.dicom.dto.dicom.response.DicomForwardResponse;
import dev.ioexception.dicom.dto.dicom.response.DicomJobResponse;
import dev.ioexception.dicom.service.dicom.DicomJobService;
import dev.ioexception.dicom.service.dicom.DicomWebService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DicomControllerTest {

    private DicomWebService dicomWebService;
    private DicomJobService dicomJobService;
    private DicomController controller;

    @BeforeEach
    void setUp() {
        dicomWebService = mock(DicomWebService.class);
        dicomJobService = mock(DicomJobService.class);
        controller = new DicomController(dicomWebService, dicomJobService);
    }

    @Test
    void submitForwardJobReturns202AcceptedWithLocationHeader() {
        DicomJob job = new DicomJob("test-job-123", "test-source", 2);
        when(dicomJobService.submitJob(eq("test-source"), any(), any())).thenReturn(job);

        ResponseEntity<DicomJobResponse> response =
                controller.submitForwardJob("test-source", List.of(), new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/dicom/jobs/test-job-123");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().jobId()).isEqualTo("test-job-123");
        assertThat(response.getBody().status()).isEqualTo(DicomJobStatus.SUBMITTED);
        assertThat(response.getBody().totalFiles()).isEqualTo(2);
    }

    @Test
    void getJobStatusReturns200OkWhenFound() {
        DicomJob job = new DicomJob("test-job-123", "test-source", 1);
        job.markProcessing();
        job.addResult(new DicomForwardResponse("1.2.3", "1.2.3.1", "1.2.3.1.1", List.of("[StudyUID: 1.2.3] 성공 -> 완료")));
        job.completeWithFinalStatus();

        when(dicomJobService.getJob("test-job-123")).thenReturn(Optional.of(job));

        ResponseEntity<DicomJobResponse> response = controller.getJobStatus("test-job-123");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().jobId()).isEqualTo("test-job-123");
        assertThat(response.getBody().status()).isEqualTo(DicomJobStatus.COMPLETED);
        assertThat(response.getBody().successCount()).isEqualTo(1);
    }

    @Test
    void getJobStatusReturns404NotFoundWhenNotFound() {
        when(dicomJobService.getJob("unknown-job")).thenReturn(Optional.empty());

        ResponseEntity<DicomJobResponse> response = controller.getJobStatus("unknown-job");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNull();
    }

    @Test
    void forwardDicomFilesAsyncDelegatesToJobServiceAndReturns202WithDeprecation() {
        DicomJob job = new DicomJob("legacy-job-456", "test-source", 1);
        when(dicomJobService.submitJob(eq("test-source"), any(), any())).thenReturn(job);

        ResponseEntity<DicomJobResponse> response =
                controller.forwardDicomFilesAsync("test-source", List.of(), false, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/dicom/jobs/legacy-job-456");
        assertThat(response.getHeaders().getFirst("Deprecation")).isEqualTo("true");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().jobId()).isEqualTo("legacy-job-456");
    }

    @Test
    void forwardDicomFilesAsyncWithRedirectReturns307TemporaryRedirect() {
        ResponseEntity<DicomJobResponse> response =
                controller.forwardDicomFilesAsync("test-source", List.of(), true, new MockHttpServletRequest());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TEMPORARY_REDIRECT);
        assertThat(response.getHeaders().getLocation()).hasToString("/api/dicom/jobs");
    }

    @Test
    void submitForwardJobIncludesXTraceIdHeaderWhenTraceExists() {
        org.slf4j.MDC.put("trace.id", "test-trace-id-12345");
        try {
            DicomJob job = new DicomJob("test-job-999", "test-source", 1);
            when(dicomJobService.submitJob(eq("test-source"), any(), any())).thenReturn(job);

            ResponseEntity<DicomJobResponse> response =
                    controller.submitForwardJob("test-source", List.of(), new MockHttpServletRequest());

            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
            assertThat(response.getHeaders().getFirst("X-Trace-Id")).isEqualTo("test-trace-id-12345");
        } finally {
            org.slf4j.MDC.remove("trace.id");
        }
    }
}
