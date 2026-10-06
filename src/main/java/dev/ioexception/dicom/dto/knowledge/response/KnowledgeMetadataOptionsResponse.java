package dev.ioexception.dicom.dto.knowledge.response;

import java.util.List;

public record KnowledgeMetadataOptionsResponse(
        List<String> authors,
        List<String> spaceKeys
) {
}
