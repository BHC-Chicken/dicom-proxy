package dev.ioexception.dicom.dto.response;

import java.util.List;

public record DicomForwardResponse(
                String studyUid,
                String seriesUid,
                String sopInstanceUid,
                List<String> results) {
}
