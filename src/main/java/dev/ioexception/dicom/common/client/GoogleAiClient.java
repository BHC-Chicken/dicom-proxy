package dev.ioexception.dicom.common.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ioexception.dicom.config.googleai.GoogleAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GoogleAiClient {

    private static final int MAX_BATCH_SIZE = 100; // Google API 1회 batchEmbedContents 최대 청크 수 제한

    private final GoogleAiProperties googleAiProperties;
    private final ObjectMapper objectMapper;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    /**
     * 단일 텍스트 쿼리를 gemini-embedding-2 모델을 통해 768차원 임베딩 벡터로 변환
     */
    public List<Float> embedContent(String text) {
        List<List<Float>> results = embedContentsBatch(List.of(text));
        if (results.isEmpty()) {
            throw new IllegalStateException("Google AI Studio로부터 임베딩 결과를 수신하지 못했습니다.");
        }

        return results.get(0);
    }

    /**
     * 여러 텍스트 청크를 100개 단위로 자동 파티셔닝하여 Google batchEmbedContents API 호출
     */
    public List<List<Float>> embedContentsBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }

        String apiKey = getValidApiKey();
        List<List<Float>> allVectors = new ArrayList<>();

        // Google API 1회 배치 제한(100개)에 맞춰 안전하게 쪼개서 송신
        for (int i = 0; i < texts.size(); i += MAX_BATCH_SIZE) {
            List<String> partition = texts.subList(i, Math.min(i + MAX_BATCH_SIZE, texts.size()));
            List<List<Float>> partitionVectors = sendBatchEmbeddingRequest(partition, apiKey);
            allVectors.addAll(partitionVectors);
        }

        return allVectors;
    }

    private List<List<Float>> sendBatchEmbeddingRequest(List<String> partition, String apiKey) {
        String model = googleAiProperties.getEmbeddingModel();
        Integer outputDim = googleAiProperties.getOutputDimensionality();
        String url = String.format(
                "https://generativelanguage.googleapis.com/v1beta/models/%s:batchEmbedContents?key=%s", model, apiKey);

        try {
            Map<String, Object> requestMap = buildBatchEmbeddingPayload(partition, model, outputDim);
            String requestBody = objectMapper.writeValueAsString(requestMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            long startTime = System.currentTimeMillis();
            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            long elapsedTime = System.currentTimeMillis() - startTime;

            if (response.statusCode() != 200) {
                log.error("Google Batch Embeddings API 호출 실패: status={}, body={}", response.statusCode(),
                        response.body());
                throw new RuntimeException("Batch Embeddings API 호출 실패: " + response.body());
            }

            List<List<Float>> batchVectors = parseBatchEmbeddingResponse(response.body());
            log.info("Google Batch 임베딩 성공: 배치 {}개 처리 (소요시간: {}ms)", partition.size(), elapsedTime);

            return batchVectors;
        } catch (Exception e) {
            log.error("Google Batch Embeddings 생성 중 예외 발생", e);

            throw new RuntimeException("Batch 임베딩 생성 오류: " + e.getMessage(), e);
        }
    }

    private Map<String, Object> buildBatchEmbeddingPayload(List<String> partition, String model, Integer outputDim) {
        List<Map<String, Object>> requestsList = new ArrayList<>();
        for (String text : partition) {
            Map<String, Object> reqItem = new HashMap<>();
            reqItem.put("model", "models/" + model);
            reqItem.put("content", Map.of("parts", List.of(Map.of("text", text))));
            if (outputDim != null && outputDim > 0) {
                reqItem.put("outputDimensionality", outputDim);
            }
            requestsList.add(reqItem);
        }

        return Map.of("requests", requestsList);
    }

    private List<List<Float>> parseBatchEmbeddingResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode embeddingsNode = root.path("embeddings");

        List<List<Float>> batchVectors = new ArrayList<>();
        if (embeddingsNode.isArray()) {
            for (JsonNode embedObj : embeddingsNode) {
                JsonNode valuesNode = embedObj.path("values");
                List<Float> vector = new ArrayList<>();
                if (valuesNode.isArray()) {
                    for (JsonNode val : valuesNode) {
                        vector.add((float) val.asDouble());
                    }
                }
                batchVectors.add(vector);
            }
        }

        return batchVectors;
    }

    /**
     * Gemini LLM 모델을 호출하여 프롬프트 기반 답변 생성
     */
    public String generateContent(String prompt) {
        String apiKey = getValidApiKey();
        String model = googleAiProperties.getChatModel();
        String url = String.format("https://generativelanguage.googleapis.com/v1beta/models/%s:generateContent?key=%s",
                model, apiKey);

        try {
            Map<String, Object> requestMap = Map.of(
                    "contents", List.of(
                            Map.of("parts", List.of(Map.of("text", prompt)))));

            String requestBody = objectMapper.writeValueAsString(requestMap);

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody, StandardCharsets.UTF_8))
                    .build();

            HttpResponse<String> response = httpClient.send(request,
                    HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

            if (response.statusCode() != 200) {
                log.error("Gemini GenerateContent API 호출 실패: status={}, body={}", response.statusCode(),
                        response.body());
                throw new RuntimeException("Gemini API 호출 실패: " + response.body());
            }

            return parseGenerateContentResponse(response.body());
        } catch (Exception e) {
            log.error("Gemini 답변 생성 중 예외 발생", e);
            throw new RuntimeException("Gemini 답변 생성 오류: " + e.getMessage(), e);
        }
    }

    private String parseGenerateContentResponse(String responseBody) throws Exception {
        JsonNode root = objectMapper.readTree(responseBody);
        JsonNode textNode = root.path("candidates")
                .path(0)
                .path("content")
                .path("parts")
                .path(0)
                .path("text");

        if (textNode.isMissingNode() || textNode.isNull()) {
            throw new IllegalStateException("Gemini 응답 텍스트 노드를 찾을 수 없습니다.");
        }

        return textNode.asText();
    }

    private String getValidApiKey() {
        String apiKey = googleAiProperties.getApiKey();

        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException("Google AI Studio API Key가 설정되지 않았습니다.");
        }

        return apiKey;
    }
}
