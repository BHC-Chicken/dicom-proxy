package dev.ioexception.dicom.dto;

import dev.ioexception.dicom.dto.response.DicomUidResponse;

public record ValidatedDicomPayload(
        DicomUidResponse representativeUid,
        String detectedBoundary,
        int partCount
) {
}
