package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.AnchorType;
import com.dalai.llama.preprod.domain.entity.ContinuityLock;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.service.videogen.shotcontext.AudioAmbience;
import com.dalai.llama.preprod.service.videogen.shotcontext.Camera;
import com.dalai.llama.preprod.service.videogen.shotcontext.ContinuityAnchor;
import com.dalai.llama.preprod.service.videogen.shotcontext.Environment;
import com.dalai.llama.preprod.service.videogen.shotcontext.Lighting;
import com.dalai.llama.preprod.service.videogen.shotcontext.Narrative;
import com.dalai.llama.preprod.service.videogen.shotcontext.Technical;

import java.util.ArrayList;
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
        String voiceCloneModel = ctx.projectConfig() == null ? null : ctx.projectConfig().getPreferredVoiceModel();
        String preferredResolution = ctx.projectConfig() == null ? null : ctx.projectConfig().getPreferredResolution();
        String ttsModel = ctx.projectConfig() == null ? null : ctx.projectConfig().getPreferredTtsModel();
        return new Technical(shot.getDurationSeconds(), shot.getAspectRatio(), null, preferredModel, voiceCloneModel, preferredResolution, ttsModel);
    }

    /** {@code musicMoodNote} stays null -- Shot has no dedicated field for it yet (creator-service's
     * real "Music mood:" prompt line, tracked as a schema gap in the pending creator-service field
     * audit, not invented here). {@code ambientDescription} was being silently dropped: {@code
     * Shot.soundDesign} is a real column, already captured at shot-list generation time -- this
     * was thrown away instead of reaching video-generation-service's prompt. */
    static AudioAmbience audioAmbience(ShotAssemblyContext ctx) {
        return new AudioAmbience(ctx.shot().getSoundDesign(), null);
    }

    /** {@code ctx.continuityLocks()} is the project's whole {@code ContinuityBible} (GLOBAL_CAMPAIGN
     * anchors, one per locked value -- character identity/wardrobe/set-prop/camera-language/
     * lighting-color, deduped and capped at generation time by {@code ContinuityBibleService}).
     * {@code ctx.previousShot()} adds PRIOR_SHOT anchors carrying forward the immediately
     * preceding shot's location/camera/lighting -- creator-service's real videoConsistencyBible +
     * previousShotContinuityContext propagation, reduced to what a single-hop lookback needs
     * rather than that system's 3-shot window. */
    static List<ContinuityAnchor> continuityAnchors(ShotAssemblyContext ctx) {
        List<ContinuityAnchor> anchors = new ArrayList<>();
        for (ContinuityLock lock : ctx.continuityLocks()) {
            anchors.add(new ContinuityAnchor(AnchorType.GLOBAL_CAMPAIGN, lock.getCategory().toString(), lock.getValue(), null));
        }
        Shot previous = ctx.previousShot();
        if (previous != null) {
            if (previous.getLocation() != null && !previous.getLocation().isBlank()) {
                anchors.add(new ContinuityAnchor(AnchorType.PRIOR_SHOT, "location",
                        "Previous shot's location: " + previous.getLocation(), null));
            }
            if (previous.getLightingMood() != null) {
                anchors.add(new ContinuityAnchor(AnchorType.PRIOR_SHOT, "lighting",
                        "Previous shot's lighting mood: " + previous.getLightingMood(), null));
            }
            if (previous.getCameraMovement() != null && !previous.getCameraMovement().isBlank()) {
                anchors.add(new ContinuityAnchor(AnchorType.PRIOR_SHOT, "camera",
                        "Previous shot's camera movement: " + previous.getCameraMovement(), null));
            }
        }
        return anchors;
    }
}
