package dev.ioexception.dicom.dto.rerank.request;

import java.util.List;

public record RerankRequest(
        String query,
        List<PassageItem> passages
) {
    public record PassageItem(
            String id,
            String content
    ) {}
}
