package dev.ioexception.dicom.controller;

import dev.ioexception.dicom.common.type.KnowledgeCategory;
import dev.ioexception.dicom.controller.swagger.KnowledgeApiDocs;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeAskRequest;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeIngestRequest;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeSearchRequest;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeAskResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeIngestResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeMetadataOptionsResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeSearchResponse;
import dev.ioexception.dicom.service.ConfluenceIngestionService;
import dev.ioexception.dicom.service.ConfluenceKnowledgeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class KnowledgeController implements KnowledgeApiDocs {

    private final ConfluenceIngestionService confluenceIngestionService;
    private final ConfluenceKnowledgeService confluenceKnowledgeService;

    @Override
    public ResponseEntity<KnowledgeIngestResponse> ingestMarkdown(
            @PathVariable KnowledgeCategory category,
            @RequestBody KnowledgeIngestRequest request
    ) {
        KnowledgeIngestResponse response = confluenceIngestionService.ingestMarkdownDocument(request, category.getIndexName());
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<KnowledgeSearchResponse> searchKnowledge(@RequestBody KnowledgeSearchRequest request) {
        KnowledgeSearchResponse response = confluenceKnowledgeService.searchKnowledge(request);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<KnowledgeAskResponse> askKnowledge(@RequestBody KnowledgeAskRequest request) {
        KnowledgeAskResponse response = confluenceKnowledgeService.askKnowledge(request);
        return ResponseEntity.ok(response);
    }

    @Override
    public ResponseEntity<KnowledgeMetadataOptionsResponse> getMetadataOptions() {
        return ResponseEntity.ok(confluenceKnowledgeService.getMetadataOptions());
    }
}
