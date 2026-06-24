package dev.ioexception.dicom.dto.response;

import java.util.List;

public record PurgeSummaryInfoResponse(int successCount, int failureCount, List<Integer> failedStudyKeys) {
}
