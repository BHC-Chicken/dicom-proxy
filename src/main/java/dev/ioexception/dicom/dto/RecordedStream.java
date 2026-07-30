package dev.ioexception.dicom.dto;

import dev.ioexception.dicom.dto.response.DicomUidResponse;

import java.io.InputStream;
import java.util.List;

public record RecordedStream(
        List<DicomUidResponse> uidsList,
        InputStream combinedStream,
        String detectedBoundary
) {
}
