package com.dalai.llama.preprod.service.videogen.shotcontext;

public record ProductBrand(
        Boolean isProductHeroShot,
        String productPlacement,
        String productRefBucket,
        String productRefObjectKey
) {
}
