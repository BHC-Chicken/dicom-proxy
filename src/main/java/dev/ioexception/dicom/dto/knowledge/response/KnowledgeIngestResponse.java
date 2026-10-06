package dev.ioexception.dicom.dto.knowledge.response;

public record KnowledgeIngestResponse(
        String title,
        int chunkCount,
        String message
) {
}
