package dev.ioexception.dicom.dto.response;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.InputStream;

public record DicomStreamResponse(
        int statusCode,
        MediaType contentType,
        long contentLength,
        InputStream inputStream) {

    public StreamingResponseBody streamingBody() {
        return outputStream -> {
            try (InputStream input = inputStream) {
                input.transferTo(outputStream);
            }
        };
    }
}
