package dev.ioexception.dicom.dto.rerank.response;

import java.util.List;

public record RerankResponse(
        List<ResultItem> results
) {
    public record ResultItem(
            String id,
            Double score
    ) {}
}
