package dev.ioexception.dicom.event;

public record DicomErrorEvent(Throwable exception, String traceId) {
}
