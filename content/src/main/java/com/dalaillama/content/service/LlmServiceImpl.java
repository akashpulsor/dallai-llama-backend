package com.dalaillama.content.service;

import com.dalaillama.content.entity.Llm;
import com.dalaillama.content.repository.LlmRepository;
import org.springframework.stereotype.Service;

import java.util.List;


@Service
public class LlmServiceImpl implements LlmService {

    private final LlmRepository llmRepository;
    public LlmServiceImpl(LlmRepository llmRepository) {
        this.llmRepository = llmRepository;
    }
    @Override
    public Llm addLlm(Llm llm) {
        return this.llmRepository.save(llm);
    }

    @Override
    public List<Llm> getAllLlm() {
        return this.llmRepository.findAll();
    }
}
