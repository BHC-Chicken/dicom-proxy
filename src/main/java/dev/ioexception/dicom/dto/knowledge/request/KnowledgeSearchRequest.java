package dev.ioexception.dicom.dto.knowledge.request;

public record KnowledgeSearchRequest(
                String query,
                Integer topK,
                String author,
                String spaceKey,
                String startDate,
                String endDate) {
}
