package dev.ioexception.dicom.dto.knowledge.response;

public record KnowledgeSearchResultItem(
        String chunkId,
        String title,
        String section,
        String headerPath,
        String content,
        String sourceUrl,
        Double score
) {
}
