package com.dalai.llama.videogen.controller;

import com.dalai.llama.videogen.service.llmgateway.LlmGatewayClient;
import com.dalai.llama.videogen.service.llmgateway.LlmGatewayModelSummary;
import com.dalai.llama.videogen.web.TenantContext;
import com.dalai.llama.videogen.web.TenantContextHolder;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Thin proxy over llm-gateway's model catalog for the video-workspace UI's "which model do we
 * generate with?" dropdown. Video-gen is the natural front door here -- it's the service that
 * actually forwards the chosen model into a dispatch request -- and the UI never talks to
 * llm-gateway directly (llm-gateway is cluster-internal). Same shape as
 * {@code post-production-service}'s equivalent {@code /v1/post-production/models} proxy used
 * for the upscale dropdown.
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/scenes")
public class ModelCatalogController {

    private final LlmGatewayClient llmGatewayClient;

    /** {@code type} filters the catalog: pass {@code video} for the video-workspace dropdown,
     * omit to see every registered model (rarely useful from the UI, kept for parity with
     * llm-gateway's own signature). */
    @GetMapping("/models")
    public ResponseEntity<List<LlmGatewayModelSummary>> listModels(@RequestParam(required = false) String type) {
        TenantContext ctx = TenantContextHolder.get();
        return ResponseEntity.ok(llmGatewayClient.listModels(ctx.tenantId().toString(), type));
    }
}
