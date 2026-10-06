package dev.ioexception.dicom.service.dicom;

import dev.ioexception.dicom.dto.dicom.response.DicomStreamResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.util.unit.DataSize;
import org.springframework.web.server.ResponseStatusException;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class DicomWebServiceTest {

    @TempDir
    Path tempDir;

    @Test
    void interruptedAcquireDoesNotIncreaseSemaphorePermits() throws Exception {
        DicomWebService service = serviceWithHttpClient(mock(HttpClient.class));
        Semaphore semaphore = semaphoreOf(service);
        semaphore.acquire();

        Path dummySpool = tempDir.resolve("dummy.part");
        java.nio.file.Files.write(dummySpool, new byte[0]);

        Method method = DicomWebService.class.getDeclaredMethod(
                "forwardStreamWithRetry", Path.class, long.class,
                String.class, String.class, String.class);
        method.setAccessible(true);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread interrupted = Thread.ofVirtual().start(() -> {
            Thread.currentThread().interrupt();
            try {
                method.invoke(service, dummySpool, 0L,
                        "multipart/related; boundary=test", "1.2.3", "source");
            } catch (InvocationTargetException e) {
                failure.set(e.getCause());
            } catch (Exception e) {
                failure.set(e);
            }
        });
        interrupted.join();

        assertThat(failure.get()).isInstanceOf(ResponseStatusException.class);
        assertThat(semaphore.availablePermits()).isZero();
        semaphore.release();
        assertThat(semaphore.availablePermits()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void wadoResponseStreamsUpstreamBodyWithoutByteArrayBuffering() throws Exception {
        HttpClient httpClient = mock(HttpClient.class);
        HttpResponse<InputStream> upstream = mock(HttpResponse.class);
        byte[] payload = "streamed-dicom".getBytes(StandardCharsets.UTF_8);
        doReturn(206).when(upstream).statusCode();
        doReturn(HttpHeaders.of(Map.of(
                "Content-Type", java.util.List.of("application/dicom"),
                "Content-Length", java.util.List.of(String.valueOf(payload.length))),
                (name, value) -> true)).when(upstream).headers();
        doReturn(new ByteArrayInputStream(payload)).when(upstream).body();
        doReturn(upstream).when(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));

        DicomStreamResponse response = serviceWithHttpClient(httpClient)
                .getWadoImage("1.2.3", "1.2.3.4", "1.2.3.4.5", null, "application/dicom");
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        response.streamingBody().writeTo(output);

        assertThat(response.statusCode()).isEqualTo(206);
        assertThat(response.contentLength()).isEqualTo(payload.length);
        assertThat(output.toByteArray()).isEqualTo(payload);
    }

    @Test
    void oversizedStowResponseIsRejectedAfterConfiguredBound() throws Exception {
        DicomWebService service = serviceWithHttpClient(mock(HttpClient.class), DataSize.ofBytes(4));
        Method method = DicomWebService.class.getDeclaredMethod(
                "readBoundedStowResponse", InputStream.class, Charset.class);
        method.setAccessible(true);

        assertThatThrownBy(() -> method.invoke(
                service, new ByteArrayInputStream("12345".getBytes(StandardCharsets.UTF_8)),
                StandardCharsets.UTF_8))
                .isInstanceOf(InvocationTargetException.class)
                .hasCauseInstanceOf(IOException.class);
    }

    @Test
    void transferWithBufferCopiesEntireStream() throws Exception {
        DicomWebService service = serviceWithHttpClient(mock(HttpClient.class));
        Method method = DicomWebService.class.getDeclaredMethod(
                "transferWithBuffer", InputStream.class, java.io.OutputStream.class, int.class);
        method.setAccessible(true);

        byte[] sourceData = new byte[256 * 1024]; // 256KB
        for (int i = 0; i < sourceData.length; i++) {
            sourceData[i] = (byte) (i % 128);
        }

        ByteArrayInputStream input = new ByteArrayInputStream(sourceData);
        ByteArrayOutputStream output = new ByteArrayOutputStream();

        method.invoke(service, input, output, 128 * 1024);

        assertThat(output.toByteArray()).isEqualTo(sourceData);
    }

    @Test
    void insufficientDiskSpaceRejectsWithServiceUnavailable() {
        // 최소 여유 공간을 10,000 테라바이트(10PB)로 설정하여 디스크 부족 상황 시뮬레이션
        DicomWebService service = serviceWithHttpClient(
                mock(HttpClient.class), DataSize.ofMegabytes(1), DataSize.ofTerabytes(10000));

        org.springframework.mock.web.MockMultipartFile file =
                new org.springframework.mock.web.MockMultipartFile(
                        "files", "test.dat", "application/octet-stream", "data".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> service.prepareSpoolFile(file))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("서버 디스크 여유 공간 부족");
    }

    @Test
    void prepareSpoolFileRejectsPayloadExceedingMaxSpoolBytes() {
        DicomWebService service = serviceWithHttpClient(
                mock(HttpClient.class), DataSize.ofMegabytes(1), DataSize.ofMegabytes(1));

        // 최대 허용치 16MB를 넘는 17MB짜리 파일 준비
        byte[] largeData = new byte[17 * 1024 * 1024];
        org.springframework.mock.web.MockMultipartFile oversizedFile =
                new org.springframework.mock.web.MockMultipartFile(
                        "files", "oversized.dat", "application/octet-stream", largeData);

        assertThatThrownBy(() -> service.prepareSpoolFile(oversizedFile))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("spool 제한을 초과했습니다");
    }

    private DicomWebService serviceWithHttpClient(HttpClient httpClient) {
        return serviceWithHttpClient(httpClient, DataSize.ofMegabytes(1));
    }

    private DicomWebService serviceWithHttpClient(HttpClient httpClient, DataSize maxStowResponseSize) {
        return serviceWithHttpClient(httpClient, maxStowResponseSize, DataSize.ofMegabytes(1));
    }

    private DicomWebService serviceWithHttpClient(HttpClient httpClient, DataSize maxStowResponseSize, DataSize minFreeDiskSpace) {
        return new DicomWebService(
                null,
                null,
                httpClient,
                "http://target.example",
                1,
                tempDir.toString(),
                DataSize.ofMegabytes(16),
                minFreeDiskSpace,
                DataSize.ofMegabytes(8),
                maxStowResponseSize,
                10,
                Duration.ofHours(24));
    }

    private Semaphore semaphoreOf(DicomWebService service) throws Exception {
        Field field = DicomWebService.class.getDeclaredField("forwardSemaphore");
        field.setAccessible(true);
        return (Semaphore) field.get(service);
    }
}
