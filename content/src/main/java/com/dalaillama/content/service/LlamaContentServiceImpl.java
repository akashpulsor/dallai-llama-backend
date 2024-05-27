package com.dalaillama.content.service;

import com.dalaillama.content.entity.LlamaContent;
import com.dalaillama.content.repository.LlamaContentRepository;
import org.springframework.stereotype.Service;


@Service
public class LlamaContentServiceImpl implements LlamaContentService {

    private final LlamaContentRepository llamaContentRepository;

    public LlamaContentServiceImpl(LlamaContentRepository llamaContentRepository) {
        this.llamaContentRepository = llamaContentRepository;
    }
    @Override
    public LlamaContent addLamaContent(LlamaContent llamaContent) {
        return this.llamaContentRepository.save(llamaContent);
    }
}
