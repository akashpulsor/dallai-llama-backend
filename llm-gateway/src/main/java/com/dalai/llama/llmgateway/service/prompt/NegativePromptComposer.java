package com.dalai.llama.llmgateway.service.prompt;

import com.dalai.llama.llmgateway.domain.LibraryScope;
import com.dalai.llama.llmgateway.dto.prompt.PromptDtos;
import com.dalai.llama.llmgateway.repository.NegativePromptLibraryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Shared negative-prompt composition, called by every {@link ProviderPromptStrategy}. Pulls
 * BASE snippets plus provider-specific snippets from {@code negative_prompt_library}, plus the
 * captions-OFF suppression directive when the effective flag says so.
 */
@Component
@RequiredArgsConstructor
public class NegativePromptComposer {

    private final NegativePromptLibraryRepository negativePromptLibraryRepository;

    public String compose(PromptDtos.ShotContext shotContext, PromptDtos.FeatureFlags flags) {
        List<String> snippets = new ArrayList<>();
        negativePromptLibraryRepository.findByScope(LibraryScope.BASE)
                .forEach(s -> snippets.add(s.getContent()));
        String targetProvider = shotContext.technical() != null ? shotContext.technical().targetProvider() : null;
        if (targetProvider != null) {
            negativePromptLibraryRepository.findByScopeAndProviderId(LibraryScope.PROVIDER, targetProvider)
                    .forEach(s -> snippets.add(s.getContent()));
        }
        // doc §19: a suppressed layer gets an explicit negative directive, not just an omission,
        // so the video model doesn't accidentally render a poorly-formed version anyway.
        if (flags != null && PromptDtos.FeatureFlags.OFF.equals(flags.captions())) {
            snippets.add("no text, no typography, no captions, no subtitles");
        }
        return snippets.stream().distinct().collect(Collectors.joining(", "));
    }
}
