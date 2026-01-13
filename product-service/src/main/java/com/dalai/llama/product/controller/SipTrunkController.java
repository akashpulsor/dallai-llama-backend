package com.dalai.llama.product.controller;

import com.dalai.llama.product.domain.entity.SipTrunk;
import com.dalai.llama.product.dto.mapper.ProductMapper;
import com.dalai.llama.product.dto.request.CreateSipTrunkRequest;
import com.dalai.llama.product.dto.response.SipTrunkResponse;
import com.dalai.llama.product.service.SipTrunkService;
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
@RequestMapping("/api/v1/tenants/{tenantId}/sip-trunks")
@RequiredArgsConstructor
@Tag(name = "SIP Trunks", description = "SIP trunk management for voice connectivity")
public class SipTrunkController {

    private final SipTrunkService sipTrunkService;
    private final ProductMapper mapper;

    @GetMapping
    @Operation(
            summary = "List tenant SIP trunks",
            description = "Returns all SIP trunks configured for the tenant"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of SIP trunks",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = SipTrunkResponse.class)))
            )
    })
    public List<SipTrunkResponse> list(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId
    ) {
        return sipTrunkService.getTrunks(tenantId)
                .stream()
                .map(mapper::toSipTrunkResponse)
                .toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(
            summary = "Create SIP trunk",
            description = "Create a custom SIP trunk for BYOC (Bring Your Own Carrier) scenarios"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "201",
                    description = "SIP trunk created",
                    content = @Content(schema = @Schema(implementation = SipTrunkResponse.class))
            ),
            @ApiResponse(responseCode = "400", description = "Invalid request")
    })
    public SipTrunkResponse create(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @RequestBody @Valid CreateSipTrunkRequest request
    ) {
        SipTrunk trunk = SipTrunk.builder()
                .name(request.getName())
                .server(request.getServer())
                .port(request.getPort())
                .transport(request.getTransport())
                .build();

        return mapper.toSipTrunkResponse(
                sipTrunkService.createTrunk(tenantId, trunk)
        );
    }

    @GetMapping("/{id}")
    @Operation(
            summary = "Get SIP trunk details",
            description = "Returns detailed information about a specific SIP trunk"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "SIP trunk details",
                    content = @Content(schema = @Schema(implementation = SipTrunkResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "SIP trunk not found")
    })
    public SipTrunkResponse get(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @Parameter(description = "SIP Trunk ID", required = true)
            @PathVariable UUID id
    ) {
        return mapper.toSipTrunkResponse(
                sipTrunkService.getTrunk(tenantId, id)
        );
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(
            summary = "Delete SIP trunk",
            description = "Delete a custom SIP trunk. DIDs using this trunk will be orphaned."
    )
    @ApiResponses({
            @ApiResponse(responseCode = "204", description = "SIP trunk deleted"),
            @ApiResponse(responseCode = "404", description = "SIP trunk not found")
    })
    public void delete(
            @Parameter(description = "Tenant ID", required = true)
            @PathVariable UUID tenantId,
            @Parameter(description = "SIP Trunk ID", required = true)
            @PathVariable UUID id
    ) {
        sipTrunkService.deleteTrunk(tenantId, id);
    }
}