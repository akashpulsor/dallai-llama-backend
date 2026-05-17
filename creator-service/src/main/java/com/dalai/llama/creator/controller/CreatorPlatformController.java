package com.dalai.llama.creator.controller;

import com.dalai.llama.creator.dto.response.CreatorPlatformResponse;
import com.dalai.llama.creator.service.CreatorPlatformService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/creator/platforms")
public class CreatorPlatformController {

    private final CreatorPlatformService platformService;

    public CreatorPlatformController(CreatorPlatformService platformService) {
        this.platformService = platformService;
    }

    @GetMapping
    public ResponseEntity<List<CreatorPlatformResponse>> listPlatforms() {
        return ResponseEntity.ok(platformService.listVisibleTargetPlatforms());
    }
}
