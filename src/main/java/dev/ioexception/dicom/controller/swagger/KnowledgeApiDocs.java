package dev.ioexception.dicom.controller.swagger;

import dev.ioexception.dicom.common.type.KnowledgeCategory;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeAskRequest;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeIngestRequest;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeSearchRequest;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeAskResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeIngestResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeMetadataOptionsResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeSearchResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

@RequestMapping("/api/v1/knowledge")
@Tag(name = "Confluence Knowledge Base & RAG API", description = "사내 지식 데이터베이스 색인, 필터 하이브리드 검색 및 RAG Q&A API")
public interface KnowledgeApiDocs {

    @Operation(summary = "카테고리별 Markdown 문서 색인", description = "Markdown 문서를 청킹하여 768차원 임베딩 생성 후 지정된 카테고리(wiki, blog)의 Elasticsearch 인덱스에 색인합니다.")
    @PostMapping("/ingest/{category}")
    ResponseEntity<KnowledgeIngestResponse> ingestMarkdown(
            @Parameter(description = "지식 카테고리 (wiki, blog)", example = "wiki")
            @PathVariable("category") KnowledgeCategory category,
            @RequestBody KnowledgeIngestRequest request);

    @Operation(summary = "지식 데이터베이스 필터 하이브리드 검색", description = "BM25 + kNN 하이브리드 검색과 함께 작성자(author), 공간(spaceKey), 날짜 범위 필터를 적용하여 연관 문서 청크를 추출합니다.")
    @PostMapping("/search")
    ResponseEntity<KnowledgeSearchResponse> searchKnowledge(@RequestBody KnowledgeSearchRequest request);

    @Operation(summary = "RAG 기반 사내 지식 Q&A", description = "필터 조건 및 연관 사내 지식 문서를 검색하여 Gemini LLM 기반의 최종 답변 및 출처를 반환합니다.")
    @PostMapping("/ask")
    ResponseEntity<KnowledgeAskResponse> askKnowledge(@RequestBody KnowledgeAskRequest request);

    @Operation(summary = "Elasticsearch 메타데이터 필터 옵션 조회", description = "색인된 문서들의 작성자(author) 및 공간 키(spaceKey) Distinct 목록을 반환합니다.")
    @GetMapping("/metadata-options")
    ResponseEntity<KnowledgeMetadataOptionsResponse> getMetadataOptions();
}
