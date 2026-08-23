package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.SuggestionTargetType;
import com.dalai.llama.preprod.dto.SuggestionTargetTypeView;
import com.dalai.llama.preprod.repository.SuggestionTargetTypeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class SuggestionTargetTypeService {

    private final SuggestionTargetTypeRepository suggestionTargetTypeRepository;

    public SuggestionTargetTypeService(SuggestionTargetTypeRepository suggestionTargetTypeRepository) {
        this.suggestionTargetTypeRepository = suggestionTargetTypeRepository;
    }

    @Transactional(readOnly = true)
    public List<SuggestionTargetTypeView> listActive() {
        return suggestionTargetTypeRepository.findByActiveTrue().stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Transactional(readOnly = true)
    public SuggestionTargetType requireActive(String code) {
        SuggestionTargetType type = suggestionTargetTypeRepository.findById(code)
                .orElseThrow(() -> PreProductionException.badRequest("Unknown suggestion target type " + code));
        if (!Boolean.TRUE.equals(type.getActive())) {
            throw PreProductionException.badRequest("Suggestion target type " + code + " is not active");
        }
        return type;
    }

    private SuggestionTargetTypeView toView(SuggestionTargetType type) {
        return new SuggestionTargetTypeView(type.getCode(), type.getLabel(), type.getDescription(),
                type.getRequiresTargetRef(), type.getTargetRefHint());
    }
}
