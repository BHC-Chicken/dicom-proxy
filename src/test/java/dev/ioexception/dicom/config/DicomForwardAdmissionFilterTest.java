package dev.ioexception.dicom.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.lang.reflect.Field;
import java.util.concurrent.Semaphore;

import static org.assertj.core.api.Assertions.assertThat;

class DicomForwardAdmissionFilterTest {

    @Test
    void formUploadIsAdmittedBeforeMultipartResolutionAndPermitIsReturned() throws Exception {
        DicomForwardAdmissionFilter filter = new DicomForwardAdmissionFilter(1);
        MockHttpServletRequest request = formUploadRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(semaphoreOf(filter).availablePermits()).isEqualTo(1);
    }

    @Test
    void interruptedAdmissionDoesNotReleaseUnownedPermit() throws Exception {
        DicomForwardAdmissionFilter filter = new DicomForwardAdmissionFilter(1);
        Semaphore semaphore = semaphoreOf(filter);
        semaphore.acquire();
        MockHttpServletResponse response = new MockHttpServletResponse();

        Thread thread = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                filter.doFilter(formUploadRequest(), response, new MockFilterChain());
            } catch (Exception e) {
                throw new AssertionError(e);
            }
        });
        thread.join();

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(semaphore.availablePermits()).isZero();
        semaphore.release();
    }

    private MockHttpServletRequest formUploadRequest() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/dicom/forward-async");
        request.setServletPath("/api/dicom/forward-async");
        request.setContentType("multipart/form-data; boundary=test");
        return request;
    }

    private Semaphore semaphoreOf(DicomForwardAdmissionFilter filter) throws Exception {
        Field field = DicomForwardAdmissionFilter.class.getDeclaredField("formUploadSemaphore");
        field.setAccessible(true);
        return (Semaphore) field.get(filter);
    }
}
