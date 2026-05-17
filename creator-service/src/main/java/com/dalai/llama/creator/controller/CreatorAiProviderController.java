package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.CreatorAiProviderResponse;
import com.dalai.llama.creator.service.CreatorAiProviderCatalogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/creator/ai-providers")
public class CreatorAiProviderController {

    private final CreatorAiProviderCatalogService providerCatalogService;

    public CreatorAiProviderController(CreatorAiProviderCatalogService providerCatalogService) {
        this.providerCatalogService = providerCatalogService;
    }

    @GetMapping
    public ResponseEntity<List<CreatorAiProviderResponse>> listProviders() {
        return ResponseEntity.ok(providerCatalogService.listVisibleProviders());
    }
}
