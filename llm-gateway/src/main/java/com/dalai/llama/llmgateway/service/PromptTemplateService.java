package com.dalai.llama.llmgateway.service;

import com.dalai.llama.llmgateway.domain.entity.PromptTemplate;
import com.dalai.llama.llmgateway.repository.PromptTemplateRepository;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Renders the active {@link PromptTemplate} for a task -- {@code {{variable}}} substitution.
 * Fails loud (never a silent empty system prompt) when no active template exists for the key,
 * since a caller that set {@code taskKey} explicitly expects real instruction text to be sent.
 */
@Service
public class PromptTemplateService {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([a-zA-Z0-9_]+)\\s*}}");

    private final PromptTemplateRepository promptTemplateRepository;

    public PromptTemplateService(PromptTemplateRepository promptTemplateRepository) {
        this.promptTemplateRepository = promptTemplateRepository;
    }

    public String renderActive(String taskKey, Map<String, String> variables) {
        PromptTemplate template = promptTemplateRepository.findByTaskKeyAndActiveTrue(taskKey)
                .orElseThrow(() -> GatewayException.notFound("No active prompt_template for task_key=" + taskKey));
        Map<String, String> vars = variables == null ? Map.of() : variables;
        Matcher matcher = PLACEHOLDER.matcher(template.getContent());
        StringBuilder result = new StringBuilder();
        while (matcher.find()) {
            String key = matcher.group(1);
            String value = vars.get(key);
            if (value == null) {
                throw GatewayException.badRequest(
                        "prompt_template task_key=%s references {{%s}} but no value was supplied".formatted(taskKey, key));
            }
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }
}
