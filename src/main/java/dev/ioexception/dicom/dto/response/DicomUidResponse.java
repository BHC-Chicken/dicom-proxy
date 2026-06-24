package dev.ioexception.dicom.dto.response;

public record DicomUidResponse(String studyUid, String seriesUid, String sopInstanceUid) {
}
