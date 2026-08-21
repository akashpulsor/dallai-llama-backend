package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;
import org.springframework.stereotype.Component;

import java.util.List;

/** ACTION, B_ROLL and TRANSITION shots assemble identically -- no dialogue character, no product
 * hero flag -- so they share one strategy bean instead of three near-identical classes. */
@Component
class NonSpeakingShotContextAssemblyStrategy implements ShotContextAssemblyStrategy {

    @Override
    public List<ShotType> supportedTypes() {
        return List.of(ShotType.ACTION, ShotType.B_ROLL, ShotType.TRANSITION);
    }

    @Override
    public ShotContext assemble(ShotAssemblyContext ctx) {
        return new ShotContext(
                ctx.shot().getShotRef(),
                ShotContextCommonFields.narrative(ctx),
                List.of(),
                ShotContextCommonFields.environment(ctx),
                ShotContextCommonFields.lighting(ctx),
                ShotContextCommonFields.camera(ctx),
                null,
                ShotContextCommonFields.technical(ctx),
                ShotContextCommonFields.continuityAnchors(ctx),
                ShotContextCommonFields.audioAmbience(ctx));
    }
}
