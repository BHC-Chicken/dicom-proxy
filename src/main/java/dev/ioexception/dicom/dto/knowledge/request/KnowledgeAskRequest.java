package dev.ioexception.dicom.dto.knowledge.request;

public record KnowledgeAskRequest(
        String query,
        Integer topK,
        String author,
        String spaceKey,
        String startDate,
        String endDate
) {
    public KnowledgeAskRequest(String query, String author, String spaceKey, String startDate, String endDate) {
        this(query, 8, author, spaceKey, startDate, endDate);
    }
}
