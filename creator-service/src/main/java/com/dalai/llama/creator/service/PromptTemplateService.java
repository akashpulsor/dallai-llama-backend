package com.dalai.llama.creator.service;

import com.dalai.llama.creator.domain.entity.CreatorPromptTemplate;
import com.dalai.llama.creator.repository.CreatorPromptTemplateRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class PromptTemplateService {

    private final CreatorPromptTemplateRepository promptTemplateRepository;
    private final ObjectMapper objectMapper;

    public PromptTemplateService(
            CreatorPromptTemplateRepository promptTemplateRepository,
            ObjectMapper objectMapper
    ) {
        this.promptTemplateRepository = promptTemplateRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public CreatorPromptTemplate getActiveTemplate(String templateKey) {
        return promptTemplateRepository.findTopByTemplateKeyAndStatusOrderByVersionDesc(templateKey, "ACTIVE")
                .orElseThrow(() -> new IllegalArgumentException("No active prompt template found for " + templateKey));
    }

    public String render(CreatorPromptTemplate template, Map<String, Object> variables) {
        String rendered = template.getTemplateBody();
        for (Map.Entry<String, Object> entry : variables.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", stringify(entry.getValue()));
        }
        return rendered;
    }

    private String stringify(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof CharSequence || value instanceof Number || value instanceof Boolean) {
            return value.toString();
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            return String.valueOf(value);
        }
    }
}
