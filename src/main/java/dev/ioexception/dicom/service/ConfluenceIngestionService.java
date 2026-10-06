package dev.ioexception.dicom.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.mapping.DenseVectorSimilarity;
import co.elastic.clients.elasticsearch.core.bulk.BulkOperation;
import dev.ioexception.dicom.common.client.GoogleAiClient;
import dev.ioexception.dicom.common.util.MarkdownChunker;
import dev.ioexception.dicom.config.cache.CacheConfig;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeIngestRequest;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeIngestResponse;
import dev.ioexception.dicom.repository.knowledge.ConfluenceKnowledgeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfluenceIngestionService {

    public static final String INDEX_NAME = "confluence_wiki";

    private final ElasticsearchClient elasticsearchClient;
    private final GoogleAiClient googleAiClient;

    /**
     * 애플리케이션 시작 시 기본 Elasticsearch 인덱스 존재 여부 점검 및 자동 생성
     */
    @PostConstruct
    public void initIndex() {
        try {
            ensureIndexExists(INDEX_NAME);
        } catch (Exception e) {
            log.error("Elasticsearch 기본 인덱스 '{}' 초기화 중 오류 발생", INDEX_NAME, e);
        }
    }

    /**
     * 기본 인덱스용 오버로딩 메서드
     */
    public KnowledgeIngestResponse ingestMarkdownDocument(KnowledgeIngestRequest request) {
        return ingestMarkdownDocument(request, INDEX_NAME);
    }

    /**
     * Confluence/Blog Markdown 문서를 동적 타겟 인덱스로 청킹하여 벡터화 후 Bulk 색인
     * (문서 추가 시 metadataOptions 캐시 자동 무효화)
     */
    @CacheEvict(value = CacheConfig.METADATA_OPTIONS_CACHE, allEntries = true)
    public KnowledgeIngestResponse ingestMarkdownDocument(KnowledgeIngestRequest request, String targetIndex) {
        String indexName = (targetIndex != null && !targetIndex.isBlank()) ? targetIndex : INDEX_NAME;

        String markdown = request.markdownContent();
        if (markdown == null || markdown.isBlank()) {
            throw new IllegalArgumentException("색인할 Markdown 내용이 비어있습니다.");
        }

        String docTitle = Optional.ofNullable(request.title())
                .filter(t -> !t.isBlank())
                .orElse("무제 문서");

        try {
            // 1. Markdown 스마트 청킹
            List<MarkdownChunker.ChunkItem> chunks = MarkdownChunker.splitMarkdown(docTitle, markdown);
            log.info("문서 '{}' 청킹 완료: 총 {}개 청크 생성 (타겟 인덱스: {})", docTitle, chunks.size(), indexName);

            if (chunks.isEmpty()) {
                return new KnowledgeIngestResponse(docTitle, 0, "청킹할 내용이 없습니다.");
            }

            // 2. Google AI Studio 임베딩 수신
            List<String> contextualTexts = chunks.stream()
                    .map(MarkdownChunker.ChunkItem::getContextualContent)
                    .toList();

            List<List<Float>> vectors = googleAiClient.embedContentsBatch(contextualTexts);

            if (vectors.size() != chunks.size()) {
                throw new IllegalStateException(
                        String.format("임베딩 수신 개수 불일치 (청크 수: %d, 수신 벡터 수: %d)", chunks.size(), vectors.size()));
            }

            // 3. ES Bulk Operation 구성 (분리된 헬퍼 메서드)
            List<BulkOperation> bulkOperations = buildBulkOperations(request, docTitle, indexName, chunks, vectors);

            // 4. ES Bulk Insert 실행
            elasticsearchClient.bulk(b -> b.operations(bulkOperations));
            log.info("ES Bulk 색인 완료: 인덱스={}, 문서='{}', 청크수={}", indexName, docTitle, chunks.size());

            return new KnowledgeIngestResponse(docTitle, chunks.size(), "성공적으로 색인되었습니다.");

        } catch (Exception e) {
            log.error("문서 색인 중 오류 발생 (인덱스: {})", indexName, e);
            throw new RuntimeException("문서 색인 실패: " + e.getMessage(), e);
        }
    }

    private List<BulkOperation> buildBulkOperations(
            KnowledgeIngestRequest request,
            String docTitle,
            String indexName,
            List<MarkdownChunker.ChunkItem> chunks,
            List<List<Float>> vectors) {

        String sourceUrl = Optional.ofNullable(request.sourceUrl()).orElse("");
        String spaceKey = Optional.ofNullable(request.spaceKey()).orElse("");
        String author = Optional.ofNullable(request.author()).orElse("");
        String createdAt = Optional.ofNullable(request.createdAt())
                .filter(c -> !c.isBlank())
                .orElseGet(() -> Instant.now().toString());
        Map<String, Object> extraMetadata = Optional.ofNullable(request.extraMetadata()).orElseGet(Map::of);

        List<BulkOperation> bulkOperations = new ArrayList<>();
        String docIdPrefix = UUID.randomUUID().toString().substring(0, 8);
        String nowIso = Instant.now().toString();

        for (int i = 0; i < chunks.size(); i++) {
            MarkdownChunker.ChunkItem chunk = chunks.get(i);
            List<Float> vector = vectors.get(i);
            String chunkId = String.format("%s_%d", docIdPrefix, chunk.getChunkIndex());

            Map<String, Object> docMap = createDocumentMap(
                    chunkId, docTitle, chunk, sourceUrl, spaceKey, author, createdAt, nowIso, vector, extraMetadata
            );

            bulkOperations.add(BulkOperation.of(b -> b
                    .index(idx -> idx
                            .index(indexName)
                            .id(chunkId)
                            .document(docMap))));
        }

        return bulkOperations;
    }

    private Map<String, Object> createDocumentMap(
            String chunkId, String docTitle, MarkdownChunker.ChunkItem chunk,
            String sourceUrl, String spaceKey, String author, String createdAt, String nowIso,
            List<Float> vector, Map<String, Object> extraMetadata) {

        Map<String, Object> metadataMap = new HashMap<>();
        metadataMap.put("doc_title", docTitle);
        metadataMap.put("section", chunk.getSection());
        metadataMap.put("header_path", chunk.getHeaderPath());
        metadataMap.put("source_url", sourceUrl);
        metadataMap.put("space_key", spaceKey);
        metadataMap.put("author", author);
        metadataMap.put("created_at", createdAt);
        metadataMap.put("indexed_at", nowIso);
        metadataMap.put("chunk_index", chunk.getChunkIndex());

        if (!extraMetadata.isEmpty()) {
            metadataMap.putAll(extraMetadata);
        }

        Map<String, Object> docMap = new HashMap<>();
        docMap.put("chunk_id", chunkId);
        docMap.put("doc_title", docTitle);
        docMap.put("section", chunk.getSection());
        docMap.put("header_path", chunk.getHeaderPath());
        docMap.put("source_url", sourceUrl);
        docMap.put("space_key", spaceKey);
        docMap.put("author", author);
        docMap.put("created_at", createdAt);
        docMap.put("indexed_at", nowIso);
        docMap.put("content", chunk.getRawContent());
        docMap.put("contextual_content", chunk.getContextualContent());
        docMap.put("content_vector", vector);
        docMap.put("metadata", metadataMap);

        return docMap;
    }

    /**
     * 지정한 인덱스가 없으면 HNSW 벡터 매핑으로 자동 생성
     */
    private void ensureIndexExists(String indexName) throws Exception {
        boolean exists = elasticsearchClient.indices().exists(e -> e.index(indexName)).value();
        if (!exists) {
            log.info("Elasticsearch 인덱스 '{}' 생성 시작...", indexName);
            elasticsearchClient.indices().create(c -> c
                    .index(indexName)
                    .mappings(m -> m
                            .properties("chunk_id", p -> p.keyword(k -> k))
                            .properties("doc_title", p -> p.text(t -> t.fields("keyword", k -> k.keyword(kk -> kk))))
                            .properties("section", p -> p.text(t -> t))
                            .properties("header_path", p -> p.text(t -> t.fields("keyword", k -> k.keyword(kk -> kk))))
                            .properties("source_url", p -> p.keyword(k -> k))
                            .properties("space_key", p -> p.keyword(k -> k))
                            .properties("author", p -> p.keyword(k -> k))
                            .properties("created_at", p -> p.date(d -> d))
                            .properties("indexed_at", p -> p.date(d -> d))
                            .properties("content", p -> p.text(t -> t))
                            .properties("contextual_content", p -> p.text(t -> t))
                            .properties("content_vector",
                                    p -> p.denseVector(
                                            d -> d.dims(768).index(true).similarity(DenseVectorSimilarity.DotProduct)))
                            .properties("metadata", p -> p.object(o -> o
                                    .properties("doc_title", mp -> mp.keyword(k -> k))
                                    .properties("section", mp -> mp.keyword(k -> k))
                                    .properties("header_path", mp -> mp.keyword(k -> k))
                                    .properties("source_url", mp -> mp.keyword(k -> k))
                                    .properties("space_key", mp -> mp.keyword(k -> k))
                                    .properties("author", mp -> mp.keyword(k -> k))
                                    .properties("created_at", mp -> mp.date(d -> d))
                                    .properties("indexed_at", mp -> mp.date(d -> d))
                                    .properties("chunk_index", mp -> mp.integer(i -> i))))));
            log.info("Elasticsearch 인덱스 '{}' 생성 완료", indexName);
        }

        // 검색용 공통 Alias(knowledge_search_alias)에 해당 인덱스 연결 보장
        try {
            String aliasName = ConfluenceKnowledgeRepository.SEARCH_ALIAS_NAME;
            elasticsearchClient.indices().updateAliases(a -> a
                    .actions(act -> act.add(add -> add.index(indexName).alias(aliasName)))
            );
        } catch (Exception e) {
            log.debug("Alias '{}' 이미 존재하거나 연결됨", ConfluenceKnowledgeRepository.SEARCH_ALIAS_NAME);
        }
    }
}
