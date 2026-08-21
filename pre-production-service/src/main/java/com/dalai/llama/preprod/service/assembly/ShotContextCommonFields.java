package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.service.videogen.shotcontext.AudioAmbience;
import com.dalai.llama.preprod.service.videogen.shotcontext.Camera;
import com.dalai.llama.preprod.service.videogen.shotcontext.Environment;
import com.dalai.llama.preprod.service.videogen.shotcontext.Lighting;
import com.dalai.llama.preprod.service.videogen.shotcontext.Narrative;
import com.dalai.llama.preprod.service.videogen.shotcontext.Technical;

import java.util.List;

/** The parts of a {@code ShotContext} that don't vary by {@link com.dalai.llama.preprod.domain.ShotType}
 * -- shared across every strategy so none of them re-derive the same fields from {@link Shot}. */
final class ShotContextCommonFields {

    private ShotContextCommonFields() {
    }

    static Narrative narrative(ShotAssemblyContext ctx) {
        Shot shot = ctx.shot();
        return new Narrative(shot.getScriptLine(), ctx.scene() == null ? null : ctx.scene().getSlug(), ctx.arcPosition());
    }

    static Environment environment(ShotAssemblyContext ctx) {
        Shot shot = ctx.shot();
        return new Environment(shot.getLocation(), shot.getTimeOfDay(), null, null);
    }

    static Lighting lighting(ShotAssemblyContext ctx) {
        return new Lighting(null, ctx.shot().getLightingMood());
    }

    static Camera camera(ShotAssemblyContext ctx) {
        Shot shot = ctx.shot();
        return new Camera(
                shot.getCameraShotSize(), shot.getCameraNote(),
                shot.getCineCameraBody(), shot.getCineSensor(), shot.getCineCaptureFormat(), shot.getCineRecordingCharacteristics(),
                shot.getCinePositionHeight(), shot.getCinePositionDistance(), shot.getCinePositionLateral(),
                shot.getCinePositionElevation(), shot.getCinePositionOrientation(),
                shot.getCineLensFocalLength(), shot.getCineLensType(), shot.getCineLensOpticalFormat(),
                shot.getCineLensDistortion(), shot.getCineLensCompression(), shot.getCineLensCharacter(),
                shot.getCineFraming(), shot.getCineSubjectPlacement(), shot.getCineHeadroom(), shot.getCineLeadRoom(), shot.getCineVisualBalance(),
                shot.getCineFocusTarget(), shot.getCineFocusDistance(), shot.getCineDepthOfField(), shot.getCineRackFocus(), shot.getCineFocusBehaviour(),
                shot.getCineMovementType(), shot.getCineMovementTrajectory(), shot.getCineMovementSpeed(),
                shot.getCineMovementAcceleration(), shot.getCineMovementRotation(), shot.getCineMovementSubjectRelationship(),
                shot.getCineSupport(),
                shot.getCineAperture(), shot.getCineIso(), shot.getCineShutter(), shot.getCineNdFilter(), shot.getCineDynamicRange(),
                shot.getCineShutterAngle(), shot.getCineMotionBlur(), shot.getCineSlowMotion(),
                shot.getCineFiltrationDiffusion(), shot.getCineFiltrationNd(), shot.getCineFiltrationPolarizer(), shot.getCineFiltrationSpecialty(),
                shot.getCineContrast(), shot.getCineColorResponse(), shot.getCineGrain(), shot.getCineHalation(),
                shot.getCineBloom(), shot.getCineSharpness(), shot.getCineFlare());
    }

    static Technical technical(ShotAssemblyContext ctx) {
        Shot shot = ctx.shot();
        String preferredModel = ctx.projectConfig() == null ? null : ctx.projectConfig().getPreferredVideoModel();
        return new Technical(shot.getDurationSeconds(), shot.getAspectRatio(), null, preferredModel);
    }

    static AudioAmbience audioAmbience(ShotAssemblyContext ctx) {
        return new AudioAmbience(null, null);
    }

    /** Full continuity-anchor propagation (doc §9's ContinuityAnchor system) is deferred past this
     * v1 slice -- every strategy returns an empty, valid list rather than guessing at anchors. */
    static List<com.dalai.llama.preprod.service.videogen.shotcontext.ContinuityAnchor> continuityAnchors(ShotAssemblyContext ctx) {
        return List.of();
    }
}
