package com.dalai.llama.critic.dto.shotcontext;

public record ProductBrand(
        Boolean isProductHeroShot,
        String productPlacement,
        String productRefBucket,
        String productRefObjectKey
) {
}
