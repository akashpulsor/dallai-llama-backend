package com.dalai.llama.agent.service;

import com.dalai.llama.agent.entity.Agent;
import com.dalai.llama.agent.repository.AgentRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PresenceService {
    private final AgentRepository repo;

    public void announcePresence(String externalId, boolean online, boolean available) {
        repo.findByExternalId(externalId).ifPresent(a -> {
            a.setOnline(online);
            a.setAvailable(available);
            repo.save(a);
        });
    }
}