package com.dalai.llama.critic.service;

import com.dalai.llama.critic.domain.entity.CritiqueThought;
import com.dalai.llama.critic.dto.CritiqueThoughtView;
import com.dalai.llama.critic.repository.CritiqueThoughtRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CritiqueThoughtService {

    private final CritiqueThoughtRepository critiqueThoughtRepository;

    public CritiqueThoughtService(CritiqueThoughtRepository critiqueThoughtRepository) {
        this.critiqueThoughtRepository = critiqueThoughtRepository;
    }

    @Transactional
    public void log(UUID tenantId, UUID sessionId, String step, String message) {
        critiqueThoughtRepository.save(CritiqueThought.builder()
                .tenantId(tenantId)
                .sessionId(sessionId)
                .step(step)
                .message(message)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<CritiqueThoughtView> list(UUID sessionId) {
        return critiqueThoughtRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(t -> new CritiqueThoughtView(t.getStep(), t.getMessage(), t.getCreatedAt()))
                .collect(Collectors.toList());
    }
}
