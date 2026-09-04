package com.dalai.llama.creativeplanning.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.UUID;

/** {@code brandContextId} is only read by {@code ProductController#create} (the standalone
 * "add a product to my library" endpoint, which brand to tag it to) -- when this same record is
 * used inline as {@code CreateStandaloneRequirementRequest.productDetails}, the brand is already
 * resolved by {@code ProjectRequirementCreationManager} from the requirement itself, and this
 * field is ignored. */
public record CreateProductRequest(
        @NotBlank String name,
        String description,
        String category,
        UUID brandContextId
) {
}
