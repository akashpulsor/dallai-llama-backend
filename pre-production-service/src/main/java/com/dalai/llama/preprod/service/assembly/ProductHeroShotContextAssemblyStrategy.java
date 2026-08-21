package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.service.videogen.shotcontext.ProductBrand;
import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;
import org.springframework.stereotype.Component;

import java.util.List;

/** PRODUCT_ASSIGNMENT (which product, which reference image) is deferred past this v1 slice --
 * {@code productRefBucket}/{@code productRefObjectKey} stay null until that entity exists, but
 * this still produces a valid, dispatchable ShotContext with the hero-shot flag and placement
 * note set from the shot's own fields. */
@Component
class ProductHeroShotContextAssemblyStrategy implements ShotContextAssemblyStrategy {

    @Override
    public List<ShotType> supportedTypes() {
        return List.of(ShotType.PRODUCT_HERO);
    }

    @Override
    public ShotContext assemble(ShotAssemblyContext ctx) {
        ProductBrand productBrand = new ProductBrand(Boolean.TRUE, ctx.shot().getScriptLine(), null, null);
        return new ShotContext(
                ctx.shot().getShotRef(),
                ShotContextCommonFields.narrative(ctx),
                List.of(),
                ShotContextCommonFields.environment(ctx),
                ShotContextCommonFields.lighting(ctx),
                ShotContextCommonFields.camera(ctx),
                productBrand,
                ShotContextCommonFields.technical(ctx),
                ShotContextCommonFields.continuityAnchors(ctx),
                ShotContextCommonFields.audioAmbience(ctx));
    }
}
