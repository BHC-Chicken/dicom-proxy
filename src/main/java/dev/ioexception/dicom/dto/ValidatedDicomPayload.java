package dev.ioexception.dicom.dto;

import dev.ioexception.dicom.dto.dicom.response.DicomUidResponse;

public record ValidatedDicomPayload(
        DicomUidResponse representativeUid,
        String detectedBoundary,
        int partCount
) {
}
