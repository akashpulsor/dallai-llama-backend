package com.dalai.llama.product.controller;

import com.dalai.llama.product.dto.request.SearchAvailableDidsRequest;
import com.dalai.llama.product.dto.response.AvailableDidResponse;
import com.dalai.llama.product.service.DidProvisioningOrchestrator;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Sort;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/did")
@RequiredArgsConstructor
@Tag(name = "DID Marketplace", description = "Search available DIDs across providers")
public class DidMarketplaceController {

    private final DidProvisioningOrchestrator provisioningOrchestrator;

    @GetMapping("/available")
    @Operation(
            summary = "Search available DIDs",
            description = "Search available phone numbers across all supported providers"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Paginated list of available DIDs",
                    content = @Content(schema = @Schema(implementation = AvailableDidResponse.class))
            )
    })
    public Page<AvailableDidResponse> searchAvailable(
            @Valid SearchAvailableDidsRequest req,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(defaultValue = "monthlyFee,asc") String[] sort
    ) {

        Sort sorting = Sort.by(
                List.of(sort).stream()
                        .map(s -> {
                            String[] parts = s.split(",");
                            return new Sort.Order(
                                    parts.length > 1 && parts[1].equalsIgnoreCase("desc")
                                            ? Sort.Direction.DESC
                                            : Sort.Direction.ASC,
                                    parts[0]
                            );
                        }).toList()
        );

        return ((com.dalai.llama.product.service.impl.DidProvisioningOrchestratorImpl)
                provisioningOrchestrator)
                .searchPaginated(req, page, size, sorting);
    }
}
