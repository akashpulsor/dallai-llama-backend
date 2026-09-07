package com.dalai.llama.preprod.service.assembly;

import com.dalai.llama.preprod.domain.ShotImageKind;
import com.dalai.llama.preprod.domain.ShotType;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.MotionGraphicPlan;
import com.dalai.llama.preprod.domain.entity.ShotImage;
import com.dalai.llama.preprod.repository.MotionGraphicPlanRepository;
import com.dalai.llama.preprod.repository.ShotImageRepository;
import com.dalai.llama.preprod.service.PreProductionException;
import com.dalai.llama.preprod.service.videogen.shotcontext.Narrative;
import com.dalai.llama.preprod.service.videogen.shotcontext.ProductBrand;
import com.dalai.llama.preprod.service.videogen.shotcontext.ShotContext;
import com.dalai.llama.preprod.service.videogen.shotcontext.Technical;
import org.springframework.stereotype.Component;

import java.util.List;

/** A MOTION_GRAPHIC shot still dispatches to video-generation-service -- it is NOT a plan-only,
 * never-rendered dead end. {@link MotionGraphicPlan} (concept/onScreenText/visualStyle/
 * animationNotes) becomes the shot's narrative line, and -- same mechanism {@link
 * ProductHeroShotContextAssemblyStrategy} already uses -- the shot's resolved {@code CastProfile}
 * (if its primaryCharacterKey names one, e.g. a product being highlighted in a data callout) is
 * attached as a reference image, so the underlying video model gets both the motion-graphics
 * prompt AND a real image to ground it on. A shot with no plan yet fails loud rather than
 * dispatching with an empty prompt -- plan first (POST /v1/shots/{shotId}/motion-graphic-plan/
 * generate), then dispatch. */
@Component
class MotionGraphicShotContextAssemblyStrategy implements ShotContextAssemblyStrategy {

    private final MotionGraphicPlanRepository motionGraphicPlanRepository;
    private final ShotImageRepository shotImageRepository;

    MotionGraphicShotContextAssemblyStrategy(
            MotionGraphicPlanRepository motionGraphicPlanRepository,
            ShotImageRepository shotImageRepository
    ) {
        this.motionGraphicPlanRepository = motionGraphicPlanRepository;
        this.shotImageRepository = shotImageRepository;
    }

    @Override
    public List<ShotType> supportedTypes() {
        return List.of(ShotType.MOTION_GRAPHIC);
    }

    @Override
    public ShotContext assemble(ShotAssemblyContext ctx) {
        MotionGraphicPlan plan = motionGraphicPlanRepository.findByShotId(ctx.shot().getId())
                .orElseThrow(() -> PreProductionException.badRequest(
                        "Shot " + ctx.shot().getShotRef() + " is a MOTION_GRAPHIC shot but has no motion graphic plan yet -- "
                                + "generate one first via POST /v1/shots/{shotId}/motion-graphic-plan/generate"));

        Narrative narrative = new Narrative(motionGraphicNarrativeLine(plan),
                ctx.scene() == null ? null : ctx.scene().getSlug(), ctx.arcPosition());

        // Reference-image priority: cast profile first (e.g. a product being highlighted in the
        // graphic already has a resolved face/product reference), then the shot's own MOTION_GRAPHIC
        // image (the design preview generated at shot-list time -- Wan/Seedance treat it as the
        // input frame for image-to-video, so the model animates the actual designed graphic rather
        // than making up a fresh interpretation of the text prompt). Falls through to no reference
        // only if neither exists, in which case the model generates from text alone.
        CastProfile reference = ctx.castProfile();
        String referenceBucket = null;
        String referenceObjectKey = null;
        if (reference != null) {
            referenceBucket = reference.getFaceRefBucket();
            referenceObjectKey = reference.getFaceRefObjectKey();
        } else {
            ShotImage mgImage = shotImageRepository.findByShotIdAndKind(ctx.shot().getId(), ShotImageKind.MOTION_GRAPHIC).orElse(null);
            if (mgImage != null) {
                referenceBucket = mgImage.getBucket();
                referenceObjectKey = mgImage.getObjectKey();
            }
        }
        boolean hasReference = referenceBucket != null && referenceObjectKey != null;
        ProductBrand productBrand = new ProductBrand(hasReference, plan.getOnScreenText(),
                referenceBucket, referenceObjectKey);

        Technical baseTechnical = ShotContextCommonFields.technical(ctx);
        Technical technical = new Technical(
                plan.getDurationSeconds() != null ? plan.getDurationSeconds() : baseTechnical.durationSeconds(),
                baseTechnical.aspectRatio(), baseTechnical.targetProvider(), baseTechnical.targetModel(),
                baseTechnical.voiceCloneModel(), baseTechnical.resolution());

        return new ShotContext(
                ctx.shot().getShotRef(),
                narrative,
                List.of(),
                ShotContextCommonFields.environment(ctx),
                ShotContextCommonFields.lighting(ctx),
                ShotContextCommonFields.camera(ctx),
                productBrand,
                technical,
                ShotContextCommonFields.continuityAnchors(ctx),
                ShotContextCommonFields.audioAmbience(ctx),
                List.of());
    }

    private String motionGraphicNarrativeLine(MotionGraphicPlan plan) {
        StringBuilder sb = new StringBuilder("Motion graphic: ");
        sb.append(plan.getConcept() == null ? "" : plan.getConcept());
        if (plan.getVisualStyle() != null && !plan.getVisualStyle().isBlank()) {
            sb.append(" Style: ").append(plan.getVisualStyle());
        }
        if (plan.getAnimationNotes() != null && !plan.getAnimationNotes().isBlank()) {
            sb.append(" Animation: ").append(plan.getAnimationNotes());
        }
        return sb.toString();
    }
}
