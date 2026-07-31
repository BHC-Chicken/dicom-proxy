package dev.ioexception.dicom.exception;

import dev.ioexception.dicom.dto.response.ExceptionResponse;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class GlobalExceptionHandlerTest {

    @Test
    void responseStatusExceptionPreservesClientStatusAndReason() {
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        GlobalExceptionHandler handler = new GlobalExceptionHandler(publisher);

        ResponseEntity<ExceptionResponse> response = handler.handleResponseStatusException(
                new ResponseStatusException(HttpStatus.BAD_REQUEST, "multiple StudyInstanceUID values"));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).isEqualTo(
                new ExceptionResponse(400, "multiple StudyInstanceUID values"));
        verifyNoInteractions(publisher);
    }
}
