package dev.ioexception.dicom.exception.dicom;

import dev.ioexception.dicom.exception.GlobalException;

public class DicomErrorException extends GlobalException {
	private final DicomErrorCode errorCode;
	private final Object additionalInfo;

	public DicomErrorException(DicomErrorCode errorCode) {
		super(errorCode);
		this.errorCode = errorCode;
		this.additionalInfo = null;
	}

	public DicomErrorException(DicomErrorCode errorCode, Object additionalInfo) {
		super(errorCode);
		this.errorCode = errorCode;
		this.additionalInfo = additionalInfo;
	}
}
