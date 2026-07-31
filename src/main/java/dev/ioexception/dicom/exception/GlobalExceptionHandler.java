package dev.ioexception.dicom.exception;

import co.elastic.apm.api.ElasticApm;
import dev.ioexception.dicom.dto.response.ExceptionResponse;
import dev.ioexception.dicom.event.DicomErrorEvent;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

@Slf4j
@RestControllerAdvice
@RequiredArgsConstructor
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {
	private final ApplicationEventPublisher eventPublisher;

	@ExceptionHandler(ResponseStatusException.class)
	public ResponseEntity<ExceptionResponse> handleResponseStatusException(ResponseStatusException e) {
		String message = e.getReason() != null ? e.getReason() : "요청을 처리할 수 없습니다.";
		return ResponseEntity.status(e.getStatusCode())
				.body(new ExceptionResponse(e.getStatusCode().value(), message));
	}

	@ExceptionHandler(GlobalException.class)
	public ResponseEntity<ExceptionResponse> handleGlobalException(Exception e, HttpServletRequest request) {
		String traceId = ElasticApm.currentTransaction().getTraceId();
		eventPublisher.publishEvent(new DicomErrorEvent(e, traceId));

		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ExceptionResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), e.getMessage()));
	}

	@ExceptionHandler(Exception.class)
	protected ResponseEntity<ExceptionResponse> handleException(Exception e) {
		log.error("예상치 못한 오류 발생: {}", e.getMessage(), e);
		String traceId = ElasticApm.currentTransaction().getTraceId();
		eventPublisher.publishEvent(new DicomErrorEvent(e, traceId));

		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(new ExceptionResponse(HttpStatus.INTERNAL_SERVER_ERROR.value(), "서버 내부 오류가 발생했습니다."));
	}
}
