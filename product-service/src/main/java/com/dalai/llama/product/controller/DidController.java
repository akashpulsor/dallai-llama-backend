package com.dalai.llama.product.controller;

import com.dalai.llama.product.dto.mapper.ProductMapper;
import com.dalai.llama.product.dto.request.ProvisionDidRequest;
import com.dalai.llama.product.dto.request.SearchAvailableDidsRequest;
import com.dalai.llama.product.dto.request.UpdateDidRoutingRequest;
import com.dalai.llama.product.dto.response.AvailableDidResponse;
import com.dalai.llama.product.dto.response.DidDetailResponse;
import com.dalai.llama.product.dto.response.DidResponse;
import com.dalai.llama.product.service.DidService;
import com.dalai.llama.product.service.didww.DidwwProvisioningService;
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
    private final DidwwProvisioningService didwwService;
    private final ProductMapper mapper;

    @GetMapping("/available")
    @Operation(
            summary = "Search available DIDs",
            description = "Search for available phone numbers from DIDWW by country, city, and type"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of available DIDs",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = AvailableDidResponse.class)))
            )
    })
    public List<AvailableDidResponse> searchAvailable(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @Valid SearchAvailableDidsRequest req
    ) {
        return didwwService.searchAvailableDids(
                        req.getCountry(),
                        req.getCity(),
                        req.getPrefix(),
                        req.getType(),
                        req.getLimit()
                )
                .getData()
                .stream()
                .map(d -> AvailableDidResponse.builder()
                        .number(d.getNumber())
                        .country(d.getCountry())
                        .city(d.getCity())
                        .type(d.getType())
                        .monthlyFee(d.getMonthlyFee())
                        .setupFee(d.getSetupFee())
                        .build()
                )
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Provision a DID",
            description = "Purchase and provision a phone number for the tenant. Creates SIP endpoint automatically."
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "DID provisioned successfully",
                    content = @Content(schema = @Schema(implementation = DidResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request or DID not available"),
            @ApiResponse(responseCode = "403", description = "Entitlement exceeded (max DIDs)")
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
                        request.getSipTrunkId() == null ? null : UUID.fromString(request.getSipTrunkId())
                )
        );
    }

    @GetMapping
    @Operation(
            summary = "List tenant DIDs",
            description = "Returns all DIDs provisioned for the tenant"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of DIDs",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = DidResponse.class)))
            )
    })
    public List<DidResponse> list(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId
    ) {
        return didService.getTenantDids(tenantId)
                .stream()
                .map(mapper::toDidResponse)
                .toList();
    }

    @GetMapping("/{didId}")
    @Operation(
            summary = "Get DID details",
            description = "Returns detailed information about a specific DID including routing config"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "DID details",
                    content = @Content(schema = @Schema(implementation = DidDetailResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "DID not found")
    })
    public DidDetailResponse get(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @Parameter(description = "DID ID", required = true)
            @PathVariable UUID didId
    ) {
        var did = didService.getDid(tenantId, didId);
        return DidDetailResponse.builder()
                .did(mapper.toDidResponse(did))
                .build();
    }

    @PatchMapping("/{didId}/routing")
    @Operation(
            summary = "Update DID routing",
            description = "Configure where calls to this DID should be routed (IVR, Queue, Agent, etc.)"
    )
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "Routing updated"),
            @ApiResponse(responseCode = "404", description = "DID not found")
    })
    public void updateRouting(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @Parameter(description = "DID ID", required = true)
            @PathVariable UUID didId,
            @RequestBody @Valid UpdateDidRoutingRequest request
    ) {
        // TODO: Implement routing update
    }

    @DeleteMapping("/{didId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Release a DID",
            description = "Release a phone number. The number will be returned to the pool."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "DID released"),
            @ApiResponse(responseCode = "404", description = "DID not found")
    })
    public void release(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @Parameter(description = "DID ID", required = true)
            @PathVariable UUID didId
    ) {
        didService.releaseDid(tenantId, didId);
    }
}