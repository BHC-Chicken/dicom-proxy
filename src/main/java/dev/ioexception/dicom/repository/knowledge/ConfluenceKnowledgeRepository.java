package dev.ioexception.dicom.repository.knowledge;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeSearchRequest;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeMetadataOptionsResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeSearchResultItem;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class ConfluenceKnowledgeRepository {

	public static final String SEARCH_ALIAS_NAME = "knowledge_search_alias";

	private final ElasticsearchClient elasticsearchClient;

	/**
	 * Elasticsearch BM25 + kNN Filtered Hybrid Search 수행 (Alias 기반 confluence_wiki & tech_blog 통합 검색)
	 */
	public List<KnowledgeSearchResultItem> searchKnowledge(KnowledgeSearchRequest request, List<Float> queryVector) {
		String query = request.query();
		int limit = Optional.ofNullable(request.topK()).filter(k -> k > 0).orElse(8);

		try {
			// Exact Filter 조건 생성 (author, space_key, created_at 날짜 범위)
			List<Query> filterQueries = buildFilterQueries(request);

			// Elasticsearch BM25 + kNN Filtered Hybrid Search 실행 (검색 전용 Alias 사용)
			SearchResponse<ObjectNode> response = elasticsearchClient.search(s -> s
							.index(SEARCH_ALIAS_NAME)
							.query(q -> q
									.bool(b -> b
											.must(m -> m
													.multiMatch(mm -> mm
															.query(query)
															.fields("content^2.0", "contextual_content^1.5", "doc_title^1.2",
																	"header_path^1.1")))
											.filter(filterQueries)))
							.knn(k -> k
									.field("content_vector")
									.queryVector(queryVector)
									.filter(filterQueries) // HNSW 벡터 탐색 사전 필터링 (Pre-filtering)
									.k(limit)
									.numCandidates(limit * 10)
									.boost(2.0f))
							.size(limit),
					ObjectNode.class);

			List<KnowledgeSearchResultItem> items = new ArrayList<>();
			for (Hit<ObjectNode> hit : response.hits().hits()) {
				ObjectNode source = hit.source();
				if (source == null) continue;

				String chunkId = source.path("chunk_id").asText(hit.id());
				String docTitle = source.path("doc_title").asText("");
				String section = source.path("section").asText("");
				String headerPath = source.path("header_path").asText("");
				String content = source.path("content").asText("");
				String sourceUrl = source.path("source_url").asText("");
				double score = hit.score() != null ? hit.score() : 0.0;

				items.add(new KnowledgeSearchResultItem(
						chunkId,
						docTitle,
						section,
						headerPath,
						content,
						sourceUrl,
						score));
			}
			return items;

		} catch (Exception e) {
			log.error("Elasticsearch 필터 하이브리드 지식 검색 실패", e);
			throw new RuntimeException("지식 검색 중 오류가 발생했습니다: " + e.getMessage(), e);
		}
	}

	/**
	 * Elasticsearch 인덱스에서 실제 존재하는 author 및 space_key Distinct 목록을 Terms Aggregation으로 조회
	 */
	public KnowledgeMetadataOptionsResponse getMetadataOptions() {
		try {
			SearchResponse<Void> response = elasticsearchClient.search(s -> s
							.index(SEARCH_ALIAS_NAME)
							.size(0)
							.aggregations("authors", a -> a.terms(t -> t.field("author").size(100)))
							.aggregations("spaceKeys", a -> a.terms(t -> t.field("space_key").size(100))),
					Void.class);

			List<String> authors = extractStringTerms(response, "authors");
			List<String> spaceKeys = extractStringTerms(response, "spaceKeys");

			return new KnowledgeMetadataOptionsResponse(authors, spaceKeys);

		} catch (Exception e) {
			log.error("Elasticsearch 메타데이터 옵션 조회 실패", e);
			return new KnowledgeMetadataOptionsResponse(List.of(), List.of());
		}
	}

	private List<String> extractStringTerms(SearchResponse<Void> response, String aggName) {
		if (response.aggregations() == null || !response.aggregations().containsKey(aggName)) {
			return Collections.emptyList();
		}

		var aggregate = response.aggregations().get(aggName);
		if (!aggregate.isSterms()) {
			return Collections.emptyList();
		}

		return aggregate.sterms().buckets().array().stream()
				.map(b -> b.key() != null ? b.key().stringValue() : null)
				.filter(val -> val != null && !val.isBlank())
				.toList();
	}

	/**
	 * 동적 Exact Filter 쿼리 생성 (author, space_key, metadata.created_at)
	 */
	private List<Query> buildFilterQueries(KnowledgeSearchRequest request) {
		List<Query> filters = new ArrayList<>();

		if (request.author() != null && !request.author().isBlank()) {
			filters.add(Query.of(q -> q.term(t -> t.field("author").value(request.author()))));
		}

		if (request.spaceKey() != null && !request.spaceKey().isBlank()) {
			filters.add(Query.of(q -> q.term(t -> t.field("space_key").value(request.spaceKey()))));
		}

		String startDate = request.startDate();
		String endDate = request.endDate();
		if ((startDate != null && !startDate.isBlank()) || (endDate != null && !endDate.isBlank())) {
			filters.add(Query.of(q -> q.range(r -> r
					.date(d -> {
						d.field("metadata.created_at");
						if (startDate != null && !startDate.isBlank()) {
							d.gte(startDate);
						}
						if (endDate != null && !endDate.isBlank()) {
							d.lte(endDate);
						}
						return d;
					}))));
		}

		return filters;
	}
}
