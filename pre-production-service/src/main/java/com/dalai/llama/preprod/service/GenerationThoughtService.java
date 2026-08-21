package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.GenerationThought;
import com.dalai.llama.preprod.dto.GenerationThoughtView;
import com.dalai.llama.preprod.repository.GenerationThoughtRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class GenerationThoughtService {

    private final GenerationThoughtRepository generationThoughtRepository;

    public GenerationThoughtService(GenerationThoughtRepository generationThoughtRepository) {
        this.generationThoughtRepository = generationThoughtRepository;
    }

    @Transactional
    public void log(UUID tenantId, UUID shotId, String step, String message) {
        generationThoughtRepository.save(GenerationThought.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .shotId(shotId)
                .step(step)
                .message(message)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    @Transactional(readOnly = true)
    public List<GenerationThoughtView> list(UUID shotId) {
        return generationThoughtRepository.findByShotIdOrderByCreatedAtAsc(shotId).stream()
                .map(t -> new GenerationThoughtView(t.getStep(), t.getMessage(), t.getCreatedAt()))
                .collect(Collectors.toList());
    }
}
