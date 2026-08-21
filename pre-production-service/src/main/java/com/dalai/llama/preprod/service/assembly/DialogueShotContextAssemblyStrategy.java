package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.videogen.shotcontext.Character;
import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;
import org.springframework.stereotype.Component;

import java.util.List;

/** A speaking character must be on screen with its cast face reference resolved -- if the shot's
 * primary character has no {@link CastAssignment} yet, this fails loud rather than silently
 * dispatching a dialogue shot with no face reference. */
@Component
class DialogueShotContextAssemblyStrategy implements ShotContextAssemblyStrategy {

    @Override
    public List<ShotType> supportedTypes() {
        return List.of(ShotType.DIALOGUE);
    }

    @Override
    public ShotContext assemble(ShotAssemblyContext ctx) {
        CastAssignment assignment = ctx.castAssignment();
        CastProfile profile = ctx.castProfile();
        if (assignment == null || profile == null) {
            throw PreProductionException.badRequest(
                    "Shot " + ctx.shot().getShotRef() + " is a DIALOGUE shot for character '"
                            + ctx.shot().getPrimaryCharacterKey() + "' but has no cast assignment yet");
        }
        Character character = new Character(
                profile.getId().toString(), profile.getFaceRefBucket(), profile.getFaceRefObjectKey(),
                assignment.getWardrobeNote(), assignment.getPerformanceDirection());

        return new ShotContext(
                ctx.shot().getShotRef(),
                ShotContextCommonFields.narrative(ctx),
                List.of(character),
                ShotContextCommonFields.environment(ctx),
                ShotContextCommonFields.lighting(ctx),
                ShotContextCommonFields.camera(ctx),
                null,
                ShotContextCommonFields.technical(ctx),
                ShotContextCommonFields.continuityAnchors(ctx),
                ShotContextCommonFields.audioAmbience(ctx));
    }
}
