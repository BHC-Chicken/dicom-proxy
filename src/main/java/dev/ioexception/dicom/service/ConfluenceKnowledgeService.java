package dev.ioexception.dicom.service;

import dev.ioexception.dicom.common.client.GoogleAiClient;
import dev.ioexception.dicom.common.client.RerankerClient;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeAskRequest;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeSearchRequest;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeAskResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeMetadataOptionsResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeSearchResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeSearchResultItem;
import dev.ioexception.dicom.config.cache.CacheConfig;
import dev.ioexception.dicom.repository.knowledge.ConfluenceKnowledgeRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfluenceKnowledgeService {

    private final ConfluenceKnowledgeRepository knowledgeRepository;
    private final GoogleAiClient googleAiClient;
    private final RerankerClient rerankerClient;

    @Value("classpath:prompts/rag-system-prompt.txt")
    private Resource ragPromptResource;

    private String ragPromptTemplate;

    @PostConstruct
    public void initPromptTemplate() {
        try {
            this.ragPromptTemplate = ragPromptResource.getContentAsString(StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.error("RAG 시스템 프롬프트 리소스 로딩 실패", e);
            throw new IllegalStateException("RAG 프롬프트 파일(prompts/rag-system-prompt.txt)을 읽을 수 없습니다.", e);
        }
    }

    /**
     * 필터 조건(작성자, spaceKey, 날짜 범위)이 적용된 2단계 하이브리드 + Reranking 지식 검색 수행
     */
    public KnowledgeSearchResponse searchKnowledge(KnowledgeSearchRequest request) {
        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("검색 쿼리가 비어있습니다.");
        }

        int targetTopK = request.topK() > 0 ? request.topK() : 8;

        // 1단계: Reranker 정밀 평가를 위해 1차 하이브리드 검색에서는 넉넉한 후보군 수집 (최소 30개 또는 요청 topK의 3배)
        int firstStageTopK = Math.max(targetTopK * 3, 30);
        KnowledgeSearchRequest firstStageRequest = new KnowledgeSearchRequest(
                query,
                firstStageTopK,
                request.author(),
                request.spaceKey(),
                request.startDate(),
                request.endDate()
        );

        // 1. 질문 쿼리 768차원 임베딩 생성
        List<Float> queryVector = googleAiClient.embedContent(query);

        // 2. 1단계 Elasticsearch 하이브리드 검색 수행 (BM25 + kNN HNSW 후보군 수집)
        List<KnowledgeSearchResultItem> firstStageItems = knowledgeRepository.searchKnowledge(firstStageRequest, queryVector);

        // 3. 2단계 Python Cross-Encoder Reranker API 호출하여 최종 재정렬 (실패 시 1차 순위 유지)
        List<KnowledgeSearchResultItem> rerankedItems = rerankerClient.rerank(query, firstStageItems, targetTopK);

        return new KnowledgeSearchResponse(query, rerankedItems);
    }

    /**
     * 필터 조건(작성자, spaceKey, 날짜 범위)이 적용된 사내 지식 기반 RAG Q&A
     */
    public KnowledgeAskResponse askKnowledge(KnowledgeAskRequest request) {
        String query = request.query();

        // 1. 지식 검색 수행
        KnowledgeSearchResponse searchResult = searchKnowledgeForAsk(request);
        List<KnowledgeSearchResultItem> sources = searchResult.items();

        if (sources.isEmpty()) {
            return new KnowledgeAskResponse(
                    query,
                    "제시된 사내 문서에서 해당 내용을 확인할 수 없습니다.",
                    List.of());
        }

        // 2. Context 텍스트 구성 (출처 표기를 위해 header_path 명시)
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < sources.size(); i++) {
            KnowledgeSearchResultItem item = sources.get(i);
            contextBuilder.append(String.format("[%d] 문서제목: %s | 경로: %s | 섹션: %s\n내용:\n%s\n\n",
                    i + 1, item.title(), item.headerPath(), item.section(), item.content()));
        }

        // 3. 외부 리소스 프롬프트 템플릿에 Context와 질문 대입 (String.format 서식문자 예외 방지)
        String ragPrompt = ragPromptTemplate
                .replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(contextBuilder.toString()))
                .replaceFirst("%s", java.util.regex.Matcher.quoteReplacement(query));

        // 4. Gemini LLM 답변 생성
        String answer = googleAiClient.generateContent(ragPrompt);
        log.info("answer: {}", answer);
        return new KnowledgeAskResponse(query, answer, sources);
    }

    private KnowledgeSearchResponse searchKnowledgeForAsk(KnowledgeAskRequest askRequest) {
        KnowledgeSearchRequest searchRequest = new KnowledgeSearchRequest(
                askRequest.query(),
                askRequest.topK(),
                askRequest.author(),
                askRequest.spaceKey(),
                askRequest.startDate(),
                askRequest.endDate());
        return searchKnowledge(searchRequest);
    }

    /**
     * Elasticsearch 메타데이터 옵션 조회 (Repository 위임 및 Caffeine Cache 1시간 적용)
     */
    @Cacheable(value = CacheConfig.METADATA_OPTIONS_CACHE)
    public KnowledgeMetadataOptionsResponse getMetadataOptions() {
        return knowledgeRepository.getMetadataOptions();
    }
}
