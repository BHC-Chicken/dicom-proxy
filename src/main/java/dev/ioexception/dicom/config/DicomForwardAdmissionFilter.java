package dev.ioexception.dicom.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.Semaphore;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
@ConditionalOnProperty(prefix = "dicom.forward.admission", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DicomForwardAdmissionFilter extends OncePerRequestFilter {

    private static final String JOBS_PATH = "/api/dicom/jobs";
    private static final String FORWARD_PATH = "/api/dicom/forward-async";

    private final Semaphore formUploadSemaphore;

    public DicomForwardAdmissionFilter(
            @Value("${dicom.forward.admission.max-concurrent-uploads:10}") int maxConcurrentUploads) {
        if (maxConcurrentUploads < 1) {
            throw new IllegalArgumentException("dicom.forward.admission.max-concurrent-uploads는 1 이상이어야 합니다.");
        }
        this.formUploadSemaphore = new Semaphore(maxConcurrentUploads);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String servletPath = request.getServletPath();
        boolean isTargetPath = JOBS_PATH.equals(servletPath) || FORWARD_PATH.equals(servletPath);
        String contentType = request.getContentType();
        return !"POST".equalsIgnoreCase(request.getMethod())
                || !isTargetPath
                || contentType == null
                || !contentType.toLowerCase(Locale.ROOT).startsWith(MediaType.MULTIPART_FORM_DATA_VALUE);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain) throws ServletException, IOException {
        boolean acquired = false;
        try {
            formUploadSemaphore.acquire();
            acquired = true;
            filterChain.doFilter(request, response);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    "DICOM form upload admission interrupted");
        } finally {
            if (acquired) {
                formUploadSemaphore.release();
            }
        }
    }
}
