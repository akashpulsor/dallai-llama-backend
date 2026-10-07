package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.music.PublicMusicPlanView;
import com.dalai.llama.preprod.service.music.PublicMusicPlanService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

/** The score plan on the client review page. Unauthenticated like the rest of {@code /v1/public/**}:
 * holding the review token is the authorization. 204 when nothing is planned yet. */
@RestController
@RequiredArgsConstructor
public class PublicMusicPlanController {

    private final PublicMusicPlanService publicMusicPlanService;

    @GetMapping("/v1/public/projects/{token}/music-plan")
    public ResponseEntity<PublicMusicPlanView> musicPlan(@PathVariable String token) {
        return publicMusicPlanService.view(token)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
