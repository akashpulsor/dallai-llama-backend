package com.dalai.llama.chat.service;

import com.dalai.llama.chat.domain.ChatScopeType;
import com.dalai.llama.chat.domain.entity.ChatSession;
import com.dalai.llama.chat.dto.ChatSessionView;
import com.dalai.llama.chat.dto.CreateChatSessionRequest;
import com.dalai.llama.chat.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class ChatSessionService {

    private final ChatSessionRepository chatSessionRepository;

    public ChatSessionService(ChatSessionRepository chatSessionRepository) {
        this.chatSessionRepository = chatSessionRepository;
    }

    @Transactional
    public ChatSessionView create(UUID tenantId, CreateChatSessionRequest request) {
        if (request.scopeType() != ChatScopeType.NONE && request.scopeId() == null) {
            throw ChatException.badRequest("scopeId is required when scopeType is " + request.scopeType());
        }
        OffsetDateTime now = OffsetDateTime.now();
        ChatSession session = chatSessionRepository.save(ChatSession.builder()
                .tenantId(tenantId)
                .scopeType(request.scopeType())
                .scopeId(request.scopeType() == ChatScopeType.NONE ? null : request.scopeId())
                .title(request.title())
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toView(session);
    }

    @Transactional(readOnly = true)
    public ChatSessionView get(UUID tenantId, UUID sessionId) {
        return toView(require(tenantId, sessionId));
    }

    @Transactional(readOnly = true)
    public List<ChatSessionView> list(UUID tenantId) {
        return chatSessionRepository.findByTenantIdOrderByUpdatedAtDesc(tenantId).stream()
                .map(this::toView)
                .toList();
    }

    public ChatSession require(UUID tenantId, UUID sessionId) {
        return chatSessionRepository.findByIdAndTenantId(sessionId, tenantId)
                .orElseThrow(() -> ChatException.notFound("No chat session " + sessionId));
    }

    @Transactional
    public void touch(ChatSession session) {
        session.setUpdatedAt(OffsetDateTime.now());
        chatSessionRepository.save(session);
    }

    private ChatSessionView toView(ChatSession session) {
        return new ChatSessionView(session.getId(), session.getScopeType(), session.getScopeId(),
                session.getTitle(), session.getCreatedAt(), session.getUpdatedAt());
    }
}
