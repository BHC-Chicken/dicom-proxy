package dev.ioexception.dicom.controller.swagger;

import dev.ioexception.dicom.dto.chat.response.ChatMessageResponse;
import dev.ioexception.dicom.dto.chat.response.ChatSessionResponse;
import dev.ioexception.dicom.dto.chat.request.SendMessageRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@RequestMapping("/api/v1/chat")
@Tag(name = "Chat System REST API", description = "챗봇 대화 세션 및 메시지 Persistence 연동 API")
public interface ChatApiDocs {

    @Operation(summary = "전체 세션 목록 조회", description = "모든 대화 세션을 최신 업데이트순으로 반환합니다.")
    @GetMapping("/sessions")
    ResponseEntity<List<ChatSessionResponse>> getSessions();

    @Operation(summary = "신규 세션 생성", description = "새로운 대화 세션을 생성합니다.")
    @PostMapping("/sessions")
    ResponseEntity<ChatSessionResponse> createSession();

    @Operation(summary = "세션 삭제", description = "지정한 대화 세션 및 대화 내역을 모두 삭제합니다.")
    @DeleteMapping("/sessions/{id}")
    ResponseEntity<Void> deleteSession(@PathVariable("id") @Parameter(description = "세션 ID") Long id);

    @Operation(summary = "세션 대화내역 조회", description = "특정 세션의 모든 질문 및 답변 메시지를 반환합니다.")
    @GetMapping("/sessions/{id}/messages")
    ResponseEntity<List<ChatMessageResponse>> getSessionMessages(@PathVariable("id") @Parameter(description = "세션 ID") Long id);

    @Operation(summary = "메시지 전송 및 RAG Q&A", description = "사용자의 질문을 수신하여 DB 저장 ➔ RAG Q&A 실행 ➔ 답변 저장 후 반환합니다.")
    @PostMapping("/send")
    ResponseEntity<ChatMessageResponse> sendMessage(@RequestBody SendMessageRequest request);
}
