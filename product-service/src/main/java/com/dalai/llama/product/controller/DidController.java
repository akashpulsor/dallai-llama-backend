package com.dalai.llama.product.controller;

import com.dalai.llama.product.dto.mapper.ProductMapper;
import com.dalai.llama.product.dto.request.ProvisionDidRequest;
import com.dalai.llama.product.dto.request.SearchAvailableDidsRequest;
import com.dalai.llama.product.dto.request.UpdateDidRoutingRequest;
import com.dalai.llama.product.dto.response.AvailableDidResponse;
import com.dalai.llama.product.dto.response.DidDetailResponse;
import com.dalai.llama.product.dto.response.DidResponse;
import com.dalai.llama.product.service.DidProvisioningOrchestrator;
import com.dalai.llama.product.service.DidService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/dids")
@RequiredArgsConstructor
@Tag(name = "DIDs", description = "Phone number (DID) provisioning and management")
public class DidController {

    private final DidService didService;
    private final ProductMapper mapper;



    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Provision a DID",
            description = "Purchase and provision a phone number for the tenant"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "DID provisioned successfully",
                    content = @Content(schema = @Schema(implementation = DidResponse.class))
            )
    })
    public DidResponse provision(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @RequestBody @Valid ProvisionDidRequest request
    ) {
        return mapper.toDidResponse(
                didService.provisionDid(
                        tenantId,
                        request.getNumber(),
                        request.getSipTrunkId() == null
                                ? null
                                : UUID.fromString(request.getSipTrunkId())
                )
        );
    }

    @GetMapping
    public List<DidResponse> list(@PathVariable UUID tenantId) {
        return didService.getTenantDids(tenantId)
                .stream()
                .map(mapper::toDidResponse)
                .toList();
    }

    @GetMapping("/{didId}")
    public DidDetailResponse get(
            @PathVariable UUID tenantId,
            @PathVariable UUID didId
    ) {
        var did = didService.getDid(tenantId, didId);
        return DidDetailResponse.builder()
                .did(mapper.toDidResponse(did))
                .build();
    }

    @DeleteMapping("/{didId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(
            @PathVariable UUID tenantId,
            @PathVariable UUID didId
    ) {
        didService.releaseDid(tenantId, didId);
    }
}
