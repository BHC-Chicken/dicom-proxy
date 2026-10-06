package dev.ioexception.dicom.dto.knowledge.response;

import java.util.List;

public record KnowledgeAskResponse(
        String query,
        String answer,
        List<KnowledgeSearchResultItem> sources
) {
}
