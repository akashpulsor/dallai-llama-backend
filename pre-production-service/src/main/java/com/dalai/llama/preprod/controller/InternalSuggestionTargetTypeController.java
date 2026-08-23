package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.SuggestionTargetTypeView;
import com.dalai.llama.preprod.service.SuggestionTargetTypeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** The master table itself, exposed for chat-service to read live (see {@code
 * SuggestionTargetType}'s javadoc for why this exists instead of a hardcoded list in chat-
 * service). Pure reference data, not tenant-scoped -- no tenantId path segment needed, unlike
 * every other internal controller in this service. */
@RestController
public class InternalSuggestionTargetTypeController {

    private final SuggestionTargetTypeService suggestionTargetTypeService;

    public InternalSuggestionTargetTypeController(SuggestionTargetTypeService suggestionTargetTypeService) {
        this.suggestionTargetTypeService = suggestionTargetTypeService;
    }

    @GetMapping("/api/v1/internal/suggestion-target-types")
    public ResponseEntity<List<SuggestionTargetTypeView>> list() {
        return ResponseEntity.ok(suggestionTargetTypeService.listActive());
    }
}
