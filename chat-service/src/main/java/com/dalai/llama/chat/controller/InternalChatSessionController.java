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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Service-to-service equivalent of {@link ChatSessionController} -- same {@code
 * /api/v1/internal/tenants/{tenantId}/...} permitAll shape every other internal controller in this
 * codebase uses, tenantId as an explicit path variable rather than JWT-derived. This is what lets
 * a service that has no chat-service JWT of its own -- pre-production-service, proxying its
 * public/unauthenticated client-review chat box -- drive a chat session on a tenant's behalf. Not
 * a parallel implementation: it delegates to the exact same {@link ChatSessionService}/{@link
 * ChatOrchestrator} the tenant-facing controller uses. */
@RestController
@RequestMapping("/api/v1/internal/tenants/{tenantId}/chat-sessions")
public class InternalChatSessionController {

    private final ChatSessionService chatSessionService;
    private final ChatOrchestrator chatOrchestrator;

    public InternalChatSessionController(ChatSessionService chatSessionService, ChatOrchestrator chatOrchestrator) {
        this.chatSessionService = chatSessionService;
        this.chatOrchestrator = chatOrchestrator;
    }

    @PostMapping
    public ResponseEntity<ChatSessionView> create(@PathVariable UUID tenantId, @Valid @RequestBody CreateChatSessionRequest request) {
        return ResponseEntity.ok(chatSessionService.create(tenantId, request));
    }

    @PostMapping("/{sessionId}/messages")
    public ResponseEntity<ChatMessageView> sendMessage(
            @PathVariable UUID tenantId, @PathVariable UUID sessionId, @Valid @RequestBody SendChatMessageRequest request) {
        return ResponseEntity.ok(chatOrchestrator.sendMessage(tenantId, sessionId, request));
    }

    @GetMapping("/{sessionId}/messages")
    public ResponseEntity<List<ChatMessageView>> messages(@PathVariable UUID tenantId, @PathVariable UUID sessionId) {
        return ResponseEntity.ok(chatOrchestrator.history(tenantId, sessionId));
    }
}
