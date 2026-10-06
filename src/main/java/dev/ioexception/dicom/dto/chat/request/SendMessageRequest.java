package dev.ioexception.dicom.dto.chat.request;

public record SendMessageRequest(
        Long sessionId,
        String query,
        Integer topK,
        String author,
        String spaceKey,
        String startDate,
        String endDate
) {
}
