package dev.ioexception.dicom.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.ioexception.dicom.domain.chat.ChatMessage;
import dev.ioexception.dicom.domain.chat.ChatSession;
import dev.ioexception.dicom.dto.chat.response.ChatMessageResponse;
import dev.ioexception.dicom.dto.chat.response.ChatSessionResponse;
import dev.ioexception.dicom.dto.chat.request.SendMessageRequest;
import dev.ioexception.dicom.dto.knowledge.request.KnowledgeAskRequest;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeAskResponse;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeSearchResultItem;
import dev.ioexception.dicom.repository.chat.ChatMessageRepository;
import dev.ioexception.dicom.repository.chat.ChatSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ChatService {

    private final ChatSessionRepository sessionRepository;
    private final ChatMessageRepository messageRepository;
    private final ConfluenceKnowledgeService confluenceKnowledgeService;
    private final ObjectMapper objectMapper;

    /**
     * 모든 대화 세션 목록 조회 (최신 업데이트순)
     */
    @Transactional(readOnly = true)
    public List<ChatSessionResponse> getSessions() {

        return sessionRepository.findAllByOrderByUpdatedAtDesc()
                .stream()
                .map(ChatSessionResponse::from)
                .toList();
    }

    /**
     * 신규 대화 세션 생성
     */
    @Transactional
    public ChatSessionResponse createSession() {
        ChatSession session = ChatSession.builder()
                .title("새로운 대화")
                .build();
        ChatSession saved = sessionRepository.save(session);

        return ChatSessionResponse.from(saved);
    }

    /**
     * 대화 세션 삭제
     */
    @Transactional
    public void deleteSession(Long sessionId) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        messageRepository.deleteAll(messages);
        sessionRepository.deleteById(sessionId);
    }

    /**
     * 특정 세션의 모든 메시지 내역 조회
     */
    @Transactional(readOnly = true)
    public List<ChatMessageResponse> getSessionMessages(Long sessionId) {
        List<ChatMessage> messages = messageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<ChatMessageResponse> result = new ArrayList<>();

        for (ChatMessage msg : messages) {
            List<KnowledgeSearchResultItem> sources = List.of();
            if (msg.getSourcesJson() != null && !msg.getSourcesJson().isBlank()) {
                try {
                    sources = objectMapper.readValue(msg.getSourcesJson(), new TypeReference<List<KnowledgeSearchResultItem>>() {});
                } catch (Exception e) {
                    log.warn("메시지 출처 JSON 파싱 실패: id={}", msg.getId(), e);
                }
            }
            result.add(ChatMessageResponse.from(msg, sources));
        }

        return result;
    }

    /**
     * 메시지 전송, Gemini RAG 질의응답 실행 및 DB 저장
     */
    @Transactional
    public ChatMessageResponse sendMessage(SendMessageRequest request) {
        String query = request.query();
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("질문 내용이 비어있습니다.");
        }

        // 1. 세션 조회/생성 및 제목 업데이트
        ChatSession session = resolveOrCreateSession(request, query);

        // 2. 필터 JSON 직렬화
        String filtersJson = serializeFiltersJson(request);

        // 3. USER 메시지 저장
        saveUserMessage(session, query, filtersJson);

        // 4. Confluence RAG 질의 실행
        KnowledgeAskRequest askRequest = new KnowledgeAskRequest(
                query,
                request.topK(),
                request.author(),
                request.spaceKey(),
                request.startDate(),
                request.endDate()
        );
        KnowledgeAskResponse askResponse = confluenceKnowledgeService.askKnowledge(askRequest);

        // 5. ASSISTANT 메시지 저장 및 응답 반환
        return saveAssistantMessage(session, askResponse, filtersJson);
    }

    private ChatSession resolveOrCreateSession(SendMessageRequest request, String query) {
        ChatSession session;
        if (request.sessionId() != null) {
            session = sessionRepository.findById(request.sessionId())
                    .orElseGet(this::createNewSessionInternal);
        } else {
            session = createNewSessionInternal();
        }

        // 첫 질문일 경우 세션 제목 업데이트 (앞 20자)
        List<ChatMessage> existingMessages = messageRepository.findBySessionIdOrderByCreatedAtAsc(session.getId());
        if (existingMessages.isEmpty() || "새로운 대화".equals(session.getTitle())) {
            String newTitle = query.length() > 20 ? query.substring(0, 20) + "..." : query;
            session.updateTitle(newTitle);
        }

        return session;
    }

    private String serializeFiltersJson(SendMessageRequest request) {
        Map<String, Object> filtersMap = new HashMap<>();
        if (request.topK() != null && request.topK() > 0) filtersMap.put("topK", request.topK());
        if (request.author() != null && !request.author().isBlank()) filtersMap.put("author", request.author());
        if (request.spaceKey() != null && !request.spaceKey().isBlank()) filtersMap.put("spaceKey", request.spaceKey());
        if (request.startDate() != null && !request.startDate().isBlank()) filtersMap.put("startDate", request.startDate());
        if (request.endDate() != null && !request.endDate().isBlank()) filtersMap.put("endDate", request.endDate());

        if (filtersMap.isEmpty()) {

            return null;
        }

        try {

            return objectMapper.writeValueAsString(filtersMap);
        } catch (Exception e) {
            log.warn("필터 JSON 직렬화 실패", e);

            return null;
        }
    }

    private void saveUserMessage(ChatSession session, String query, String filtersJson) {
        ChatMessage userMsg = ChatMessage.builder()
                .session(session)
                .role("USER")
                .content(query)
                .filtersJson(filtersJson)
                .build();
        messageRepository.save(userMsg);
    }

    private ChatMessageResponse saveAssistantMessage(ChatSession session, KnowledgeAskResponse askResponse, String filtersJson) {
        String sourcesJson = null;
        try {
            if (askResponse.sources() != null && !askResponse.sources().isEmpty()) {
                sourcesJson = objectMapper.writeValueAsString(askResponse.sources());
            }
        } catch (Exception e) {
            log.warn("출처 JSON 직렬화 실패", e);
        }

        ChatMessage assistantMsg = ChatMessage.builder()
                .session(session)
                .role("ASSISTANT")
                .content(askResponse.answer())
                .filtersJson(filtersJson)
                .sourcesJson(sourcesJson)
                .build();
        ChatMessage savedAssistantMsg = messageRepository.save(assistantMsg);

        return ChatMessageResponse.from(savedAssistantMsg, askResponse.sources());
    }

    private ChatSession createNewSessionInternal() {
        ChatSession session = ChatSession.builder()
                .title("새로운 대화")
                .build();

        return sessionRepository.save(session);
    }
}
