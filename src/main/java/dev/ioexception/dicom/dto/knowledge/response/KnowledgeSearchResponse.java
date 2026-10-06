package dev.ioexception.dicom.dto.knowledge.response;

import java.util.List;

public record KnowledgeSearchResponse(
        String query,
        List<KnowledgeSearchResultItem> items
) {
}
