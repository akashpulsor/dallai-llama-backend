package com.dalai.llama.llmgateway.controller;

import com.dalai.llama.llmgateway.dto.RateCardView;
import com.dalai.llama.llmgateway.service.ratecard.RateCardQueryService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/** Service-to-service: the current rates of a model (model ids carry slashes, so it is a query
 * parameter). billing-service reads these to estimate a video's production cost. */
@RestController
@RequestMapping("/api/v1/internal")
public class InternalRateCardController {

    private final RateCardQueryService rateCardQueryService;

    public InternalRateCardController(RateCardQueryService rateCardQueryService) {
        this.rateCardQueryService = rateCardQueryService;
    }

    @GetMapping("/rate-cards")
    public ResponseEntity<List<RateCardView>> current(@RequestParam String modelId) {
        return ResponseEntity.ok(rateCardQueryService.current(modelId));
    }
}
