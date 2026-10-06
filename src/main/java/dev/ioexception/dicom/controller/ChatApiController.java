package dev.ioexception.dicom.controller;

import dev.ioexception.dicom.controller.swagger.ChatApiDocs;
import dev.ioexception.dicom.dto.chat.response.ChatMessageResponse;
import dev.ioexception.dicom.dto.chat.response.ChatSessionResponse;
import dev.ioexception.dicom.dto.chat.request.SendMessageRequest;
import dev.ioexception.dicom.service.ChatService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class ChatApiController implements ChatApiDocs {

    private final ChatService chatService;

    @Override
    public ResponseEntity<List<ChatSessionResponse>> getSessions() {
        return ResponseEntity.ok(chatService.getSessions());
    }

    @Override
    public ResponseEntity<ChatSessionResponse> createSession() {
        return ResponseEntity.ok(chatService.createSession());
    }

    @Override
    public ResponseEntity<Void> deleteSession(@PathVariable("id") Long id) {
        chatService.deleteSession(id);
        return ResponseEntity.noContent().build();
    }

    @Override
    public ResponseEntity<List<ChatMessageResponse>> getSessionMessages(@PathVariable("id") Long id) {
        return ResponseEntity.ok(chatService.getSessionMessages(id));
    }

    @Override
    public ResponseEntity<ChatMessageResponse> sendMessage(@RequestBody SendMessageRequest request) {
        return ResponseEntity.ok(chatService.sendMessage(request));
    }
}
