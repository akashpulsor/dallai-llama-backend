package com.dalai.llama.product.controller;

import com.dalai.llama.product.dto.mapper.ProductMapper;
import com.dalai.llama.product.dto.response.ProductResponse;
import com.dalai.llama.product.service.ProductService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/products")
@RequiredArgsConstructor
@Tag(name = "Products", description = "Product catalog management")
public class ProductController {

    private final ProductService productService;
    private final ProductMapper mapper;

    @GetMapping
    @Operation(
            summary = "List all products",
            description = "Returns all active products in the catalog"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "List of products",
                    content = @Content(array = @ArraySchema(schema = @Schema(implementation = ProductResponse.class)))
            )
    })
    public List<ProductResponse> getAllProducts() {
        return productService.getAllActiveProducts()
                .stream()
                .map(mapper::toProductResponse)
                .toList();
    }

    @GetMapping("/{code}")
    @Operation(
            summary = "Get product by code",
            description = "Returns a specific product by its unique code"
    )
    @ApiResponses({
            @ApiResponse(
                    responseCode = "200",
                    description = "Product found",
                    content = @Content(schema = @Schema(implementation = ProductResponse.class))
            ),
            @ApiResponse(responseCode = "404", description = "Product not found")
    })
    public ProductResponse getProduct(
            @Parameter(description = "Product code (e.g., AI_CC, CONV_IVR, BASIC_PBX)")
            @PathVariable String code
    ) {
        return mapper.toProductResponse(productService.getByCode(code));
    }
}