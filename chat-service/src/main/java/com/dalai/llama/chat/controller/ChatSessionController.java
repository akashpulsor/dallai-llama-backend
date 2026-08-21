package com.dalai.llama.chat.controller;

import com.dalai.llama.chat.dto.ChatMessageView;
import com.dalai.llama.chat.dto.ChatSessionView;
import com.dalai.llama.chat.dto.CreateChatSessionRequest;
import com.dalai.llama.chat.dto.SendChatMessageRequest;
import com.dalai.llama.chat.service.ChatOrchestrator;
import com.dalai.llama.chat.service.ChatSessionService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
public class ChatSessionController extends BaseController {

    private final ChatSessionService chatSessionService;
    private final ChatOrchestrator chatOrchestrator;

    public ChatSessionController(ChatSessionService chatSessionService, ChatOrchestrator chatOrchestrator) {
        this.chatSessionService = chatSessionService;
        this.chatOrchestrator = chatOrchestrator;
    }

    @PostMapping("/v1/chat-sessions")
    public ResponseEntity<ChatSessionView> create(@Valid @RequestBody CreateChatSessionRequest request) {
        return ResponseEntity.ok(chatSessionService.create(tenant().tenantId(), request));
    }

    @GetMapping("/v1/chat-sessions/{sessionId}")
    public ResponseEntity<ChatSessionView> get(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(chatSessionService.get(tenant().tenantId(), sessionId));
    }

    @GetMapping("/v1/chat-sessions")
    public ResponseEntity<List<ChatSessionView>> list() {
        return ResponseEntity.ok(chatSessionService.list(tenant().tenantId()));
    }

    @PostMapping("/v1/chat-sessions/{sessionId}/messages")
    public ResponseEntity<ChatMessageView> sendMessage(@PathVariable UUID sessionId, @Valid @RequestBody SendChatMessageRequest request) {
        return ResponseEntity.ok(chatOrchestrator.sendMessage(tenant().tenantId(), sessionId, request));
    }

    @GetMapping("/v1/chat-sessions/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageView>> messages(@PathVariable UUID sessionId) {
        return ResponseEntity.ok(chatOrchestrator.history(tenant().tenantId(), sessionId));
    }
}
