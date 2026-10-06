package dev.ioexception.dicom.common.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeSearchResultItem;
import dev.ioexception.dicom.dto.rerank.request.RerankRequest;
import dev.ioexception.dicom.dto.rerank.response.RerankResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
public class RerankerClient {

    private final ObjectMapper objectMapper;

    @Value("${app.reranker.url:http://localhost:8000/rerank}")
    private String rerankerUrl;

    @Value("${app.reranker.enabled:true}")
    private boolean rerankerEnabled;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 1차 하이브리드 검색 결과(items)를 8000번 Python Reranker API로 전달하여 Cross-Encoder 정밀 점수로
     * 재정렬
     */
    public List<KnowledgeSearchResultItem> rerank(String query, List<KnowledgeSearchResultItem> items, int targetTopK) {
        if (!rerankerEnabled || items == null || items.size() <= 1) {

            return truncateList(items, targetTopK);
        }

        try {
            // 1. Rerank API 호출 및 응답 수신
            RerankResponse rerankResponse = executeRerankApi(query, items);
            if (rerankResponse == null || rerankResponse.results() == null || rerankResponse.results().isEmpty()) {

                return truncateList(items, targetTopK);
            }

            // 2. 응답 점수 기반 아이템 재정렬
            List<KnowledgeSearchResultItem> rerankedItems = applyRerankedScores(items, rerankResponse);
            log.info("Reranking 완료! 1차 후보 {}개 중 최상위 {}개 재정렬 반환", items.size(),
                    Math.min(rerankedItems.size(), targetTopK));

            return truncateList(rerankedItems, targetTopK);

        } catch (Exception e) {
            log.warn("Reranker 호출 실패 ({}: {}). 1차 검색 순위를 유지합니다.", e.getClass().getSimpleName(), e.getMessage());

            return truncateList(items, targetTopK);
        }
    }

    private RerankResponse executeRerankApi(String query, List<KnowledgeSearchResultItem> items) throws Exception {
        RerankRequest rerankPayload = buildRerankPayload(query, items);
        String requestBodyJson = objectMapper.writeValueAsString(rerankPayload);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(rerankerUrl))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(15))
                .POST(HttpRequest.BodyPublishers.ofString(requestBodyJson, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() != 200) {
            log.warn("Reranker API 응답 오류 (HTTP Status: {}). 1차 검색 순위를 유지합니다.", response.statusCode());

            return null;
        }

        return objectMapper.readValue(response.body(), RerankResponse.class);
    }

    private RerankRequest buildRerankPayload(String query, List<KnowledgeSearchResultItem> items) {
        List<RerankRequest.PassageItem> passages = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            KnowledgeSearchResultItem item = items.get(i);
            String passageId = "chunk_" + i;
            passages.add(new RerankRequest.PassageItem(passageId, item.content()));
        }

        return new RerankRequest(query, passages);
    }

    private List<KnowledgeSearchResultItem> applyRerankedScores(List<KnowledgeSearchResultItem> items,
            RerankResponse rerankResponse) {
        Map<String, Double> scoreMap = rerankResponse.results().stream()
                .collect(
                        Collectors.toMap(RerankResponse.ResultItem::id, RerankResponse.ResultItem::score, (a, b) -> a));

        List<KnowledgeSearchResultItem> rerankedItems = new ArrayList<>();
        for (int i = 0; i < items.size(); i++) {
            KnowledgeSearchResultItem originalItem = items.get(i);
            String passageId = "chunk_" + i;
            Double rerankScore = scoreMap.getOrDefault(passageId, originalItem.score());

            rerankedItems.add(new KnowledgeSearchResultItem(
                    originalItem.chunkId(),
                    originalItem.title(),
                    originalItem.section(),
                    originalItem.headerPath(),
                    originalItem.content(),
                    originalItem.sourceUrl(),
                    rerankScore));
        }

        rerankedItems.sort(Comparator.comparingDouble(KnowledgeSearchResultItem::score).reversed());

        return rerankedItems;
    }

    private List<KnowledgeSearchResultItem> truncateList(List<KnowledgeSearchResultItem> items, int targetTopK) {
        if (items == null) {

            return List.of();
        }

        return items.stream().limit(targetTopK).toList();
    }
}
