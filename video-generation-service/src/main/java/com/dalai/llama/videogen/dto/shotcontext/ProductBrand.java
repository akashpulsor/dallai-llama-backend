package com.dalai.llama.videogen.dto.shotcontext;

public record ProductBrand(
        Boolean isProductHeroShot,
        String productPlacement,
        String productRefBucket,
        String productRefObjectKey
) {
}
