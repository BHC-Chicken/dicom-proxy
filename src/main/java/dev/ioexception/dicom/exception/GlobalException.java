package dev.ioexception.dicom.exception;

import lombok.Getter;

@Getter
public class GlobalException extends RuntimeException {
	private final GlobalErrorCode errorCode;

	public GlobalException(GlobalErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
