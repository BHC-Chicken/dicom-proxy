package dev.ioexception.dicom.dto.dicom.response;

import java.util.List;

public record DicomForwardResponse(
                String studyUid,
                String seriesUid,
                String sopInstanceUid,
                List<String> results) {

    public boolean isSuccess() {
        return results != null && results.stream().anyMatch(r -> r.contains("성공 ->"));
    }
}
