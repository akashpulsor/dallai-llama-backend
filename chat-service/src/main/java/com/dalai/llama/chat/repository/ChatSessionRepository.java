package com.dalai.llama.chat.repository;

import com.dalai.llama.chat.domain.entity.ChatSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ChatSessionRepository extends JpaRepository<ChatSession, UUID> {

    Optional<ChatSession> findByIdAndTenantId(UUID id, UUID tenantId);

    List<ChatSession> findByTenantIdOrderByUpdatedAtDesc(UUID tenantId);
}
