package dev.ioexception.dicom.dto.chat.response;

import dev.ioexception.dicom.domain.chat.ChatMessage;
import dev.ioexception.dicom.dto.knowledge.response.KnowledgeSearchResultItem;

import java.time.LocalDateTime;
import java.util.List;

public record ChatMessageResponse(
        Long id,
        Long sessionId,
        String role,
        String content,
        String filtersJson,
        List<KnowledgeSearchResultItem> sources,
        LocalDateTime createdAt
) {
    public static ChatMessageResponse from(ChatMessage message, List<KnowledgeSearchResultItem> sources) {
        return new ChatMessageResponse(
                message.getId(),
                message.getSession().getId(),
                message.getRole(),
                message.getContent(),
                message.getFiltersJson(),
                sources,
                message.getCreatedAt()
        );
    }
}
