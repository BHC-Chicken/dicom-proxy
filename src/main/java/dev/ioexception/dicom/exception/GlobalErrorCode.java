package dev.ioexception.dicom.exception;

import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;

public interface GlobalErrorCode {
	HttpStatus getHttpStatus();
	String getMessage();
	Level getLevel();
}
