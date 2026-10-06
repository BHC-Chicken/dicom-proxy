package dev.ioexception.dicom.dto.knowledge.request;

import java.util.Map;

public record KnowledgeIngestRequest(
        String title,
        String sourceUrl,
        String spaceKey,
        String author,
        String createdAt,
        String markdownContent,
        Map<String, Object> extraMetadata
) {
}
