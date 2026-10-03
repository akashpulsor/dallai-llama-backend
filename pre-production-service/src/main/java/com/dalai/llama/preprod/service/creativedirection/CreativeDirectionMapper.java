package com.dalai.llama.preprod.service.creativedirection;

import com.dalai.llama.preprod.domain.entity.CreativeDirection;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionFeedback;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionGeneration;
import com.dalai.llama.preprod.domain.entity.CreativeDirectionReference;
import com.dalai.llama.preprod.dto.ApprovedCreativeDirectionContext;
import com.dalai.llama.preprod.dto.CreativeDirectionBoardView;
import com.dalai.llama.preprod.dto.CreativeDirectionView;
import com.dalai.llama.preprod.service.creativeplanning.CreativePlanningClient.CreativeContext.ReferenceAsset;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Creative-direction entities -> their API views and the downstream generation context. The one
 * place the column-by-column shape of a treatment is read, so no other class re-assembles it. */
@Component
public class CreativeDirectionMapper {

    /** {@code liveAssets} supplies fresh signed URLs by asset id; an asset missing from it (or an
     * empty map when creative-planning is unreachable) renders with a null URL, never a made-up one. */
    public CreativeDirectionView toView(CreativeDirection direction, List<CreativeDirectionReference> references,
                                        List<CreativeDirectionFeedback> feedback, Map<UUID, ReferenceAsset> liveAssets) {
        return new CreativeDirectionView(
                direction.getId(), direction.getGenerationId(), direction.getOptionNumber(), direction.getVersion(),
                direction.getRevisedFromId(), direction.getTitle(), direction.getCreativeConcept(),
                direction.getDirectorsTreatment(), direction.getStorytellingStyle(),
                new CreativeDirectionView.VisualLanguage(direction.getStoryPeriod(), direction.getColorTreatment(),
                        direction.getContrast(), direction.getTexture(), direction.getOverallAesthetic()),
                direction.getCinematographyPhilosophy(), direction.getEmotionalJourney(), direction.getSoundDirection(),
                direction.getSignatureCreativeDevice(), direction.getCreativeRationale(),
                direction.isRecommended(), direction.getRecommendationReason(), direction.getReviewStatus(),
                direction.getApprovedAt(), direction.getApprovedVia(),
                references.stream().map(reference -> toReferenceView(reference, liveAssets.get(reference.getAssetId()))).toList(),
                feedback.stream().map(note -> new CreativeDirectionView.Feedback(note.getId(), note.getSource(),
                        note.getFeedback(), note.getCreatedAt())).toList(),
                direction.getCreatedAt());
    }

    public ApprovedCreativeDirectionContext toContext(CreativeDirection direction, CreativeDirectionGeneration generation,
                                                      List<CreativeDirectionReference> references) {
        return new ApprovedCreativeDirectionContext(
                direction.getId(), direction.getVersion(),
                generation == null ? null : new ApprovedCreativeDirectionContext.Idea(generation.getIdeaTitle(),
                        generation.getIdeaConcept(), generation.getIdeaTargetAudience(), generation.getIdeaCampaignAngle(),
                        generation.getIdeaKeyMessage(), generation.getIdeaTone()),
                generation == null ? null : generation.getBriefText(),
                direction.getTitle(), direction.getCreativeConcept(), direction.getDirectorsTreatment(),
                direction.getStorytellingStyle(), direction.getStoryPeriod(), direction.getColorTreatment(),
                direction.getContrast(), direction.getTexture(), direction.getOverallAesthetic(),
                direction.getCinematographyPhilosophy(), direction.getEmotionalJourney(), direction.getSoundDirection(),
                direction.getSignatureCreativeDevice(), direction.getCreativeRationale(),
                references.stream().map(reference -> new ApprovedCreativeDirectionContext.Reference(reference.getAssetId(),
                        reference.getMediaType(), reference.getBucket(), reference.getObjectKey(),
                        reference.getClientInstruction(), reference.getReferenceAnalysis())).toList());
    }

    public CreativeDirectionBoardView.Idea toBoardIdea(CreativeDirectionGeneration generation) {
        return generation == null ? null : new CreativeDirectionBoardView.Idea(generation.getIdeaTitle(),
                generation.getIdeaConcept(), generation.getIdeaTargetAudience(), generation.getIdeaCampaignAngle(),
                generation.getIdeaKeyMessage(), generation.getIdeaTone());
    }

    private static CreativeDirectionView.Reference toReferenceView(CreativeDirectionReference reference, ReferenceAsset live) {
        return new CreativeDirectionView.Reference(reference.getAssetId(), reference.getMediaType(),
                live == null ? null : live.signedUrl(),
                live == null ? null : live.contentType(),
                live == null ? null : live.originalFilename(),
                reference.getClientInstruction(), reference.getReferenceAnalysis());
    }
}
