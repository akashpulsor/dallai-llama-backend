package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.service.videogen.shotcontext.ProductBrand;
import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;
import org.springframework.stereotype.Component;

import java.util.List;

/** Which product, which reference image is resolved the exact same way {@link
 * DialogueShotContextAssemblyStrategy} resolves a speaking character's face: the shot's {@code
 * primaryCharacterKey} names a PRODUCT-typed {@code ScriptCharacter}, {@code
 * ShotContextAssemblyService} resolves its {@code CastAssignment} -> {@code CastProfile} (a
 * PRODUCT profile has a product photo where an ACTOR profile has a face), and {@code
 * ctx.castProfile()} carries it here. {@code productRefBucket}/{@code productRefObjectKey} stay
 * null only when the shot has no primary character or that character has no assignment yet --
 * still a valid, dispatchable ShotContext, just with no product image resolved. */
@Component
class ProductHeroShotContextAssemblyStrategy implements ShotContextAssemblyStrategy {

    @Override
    public List<ShotType> supportedTypes() {
        return List.of(ShotType.PRODUCT_HERO);
    }

    @Override
    public ShotContext assemble(ShotAssemblyContext ctx) {
        CastProfile productProfile = ctx.castProfile();
        ProductBrand productBrand = new ProductBrand(
                Boolean.TRUE, ctx.shot().getScriptLine(),
                productProfile == null ? null : productProfile.getFaceRefBucket(),
                productProfile == null ? null : productProfile.getFaceRefObjectKey());
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
                ShotContextCommonFields.audioAmbience(ctx),
                List.of());
    }
}
