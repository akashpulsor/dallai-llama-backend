package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.AspectRatioOptionView;
import com.dalai.llama.preprod.dto.DialogueLanguageView;
import com.dalai.llama.preprod.dto.GenderOptionView;
import com.dalai.llama.preprod.dto.ShotTypeDefinitionView;
import com.dalai.llama.preprod.dto.VideoFeatureFlagDefinitionView;
import com.dalai.llama.preprod.service.MasterDataService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Read-only reference data for the project-settings and video-generation UIs -- tenant-
 * authenticated like any other GET (unlike {@code suggestion_target_type}, nothing outside this
 * service needs these, so there's no internal/permitAll variant). */
@RestController
public class MasterDataController {

    private final MasterDataService masterDataService;

    public MasterDataController(MasterDataService masterDataService) {
        this.masterDataService = masterDataService;
    }

    @GetMapping("/v1/shot-types")
    public ResponseEntity<List<ShotTypeDefinitionView>> shotTypes() {
        return ResponseEntity.ok(masterDataService.listShotTypes());
    }

    @GetMapping("/v1/video-feature-flags")
    public ResponseEntity<List<VideoFeatureFlagDefinitionView>> videoFeatureFlags() {
        return ResponseEntity.ok(masterDataService.listVideoFeatureFlags());
    }

    @GetMapping("/v1/aspect-ratios")
    public ResponseEntity<List<AspectRatioOptionView>> aspectRatios() {
        return ResponseEntity.ok(masterDataService.listAspectRatios());
    }

    @GetMapping("/v1/dialogue-languages")
    public ResponseEntity<List<DialogueLanguageView>> dialogueLanguages() {
        return ResponseEntity.ok(masterDataService.listDialogueLanguages());
    }

    @GetMapping("/v1/genders")
    public ResponseEntity<List<GenderOptionView>> genders() {
        return ResponseEntity.ok(masterDataService.listGenders());
    }
}
