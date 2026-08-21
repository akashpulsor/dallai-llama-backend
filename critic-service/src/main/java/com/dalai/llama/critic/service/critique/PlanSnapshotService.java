package com.dalai.llama.critic.service.critique;

import com.dalai.llama.critic.domain.PlanSnapshotKind;
import com.dalai.llama.critic.domain.entity.CritiquePlanCharacter;
import com.dalai.llama.critic.domain.entity.CritiquePlanContinuityAnchor;
import com.dalai.llama.critic.domain.entity.CritiquePlanSnapshot;
import com.dalai.llama.critic.dto.shotcontext.AudioAmbience;
import com.dalai.llama.critic.dto.shotcontext.Camera;
import com.dalai.llama.critic.dto.shotcontext.Character;
import com.dalai.llama.critic.dto.shotcontext.ContinuityAnchor;
import com.dalai.llama.critic.dto.shotcontext.Environment;
import com.dalai.llama.critic.dto.shotcontext.Lighting;
import com.dalai.llama.critic.dto.shotcontext.Narrative;
import com.dalai.llama.critic.dto.shotcontext.ProductBrand;
import com.dalai.llama.critic.dto.shotcontext.ShotContext;
import com.dalai.llama.critic.dto.shotcontext.Technical;
import com.dalai.llama.critic.repository.CritiquePlanCharacterRepository;
import com.dalai.llama.critic.repository.CritiquePlanContinuityAnchorRepository;
import com.dalai.llama.critic.repository.CritiquePlanSnapshotRepository;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The only place a {@code ShotContext} is taken apart into relational rows or reassembled from
 * them -- every other class deals in the typed DTO. Keeps {@link CritiqueOrchestrator} free of
 * flattening/rebuilding logic and gives this mapping exactly one owner.
 */
@Service
public class PlanSnapshotService {

    private final CritiquePlanSnapshotRepository snapshotRepository;
    private final CritiquePlanCharacterRepository characterRepository;
    private final CritiquePlanContinuityAnchorRepository continuityAnchorRepository;

    public PlanSnapshotService(
            CritiquePlanSnapshotRepository snapshotRepository,
            CritiquePlanCharacterRepository characterRepository,
            CritiquePlanContinuityAnchorRepository continuityAnchorRepository
    ) {
        this.snapshotRepository = snapshotRepository;
        this.characterRepository = characterRepository;
        this.continuityAnchorRepository = continuityAnchorRepository;
    }

    public void persist(UUID sessionId, PlanSnapshotKind kind, ShotContext plan) {
        Narrative narrative = plan.narrative();
        Environment environment = plan.environment();
        Lighting lighting = plan.lighting();
        Camera camera = plan.camera();
        ProductBrand productBrand = plan.productBrand();
        Technical technical = plan.technical();
        AudioAmbience audioAmbience = plan.audioAmbience();

        UUID snapshotId = UUID.randomUUID();
        CritiquePlanSnapshot.CritiquePlanSnapshotBuilder builder = CritiquePlanSnapshot.builder()
                .id(snapshotId)
                .sessionId(sessionId)
                .kind(kind)
                .shotRef(plan.shotRef())
                .narrativeScriptLine(narrative == null ? null : narrative.scriptLine())
                .narrativeScreenplaySlug(narrative == null ? null : narrative.screenplaySlug())
                .narrativeArcPosition(narrative == null ? null : narrative.arcPosition())
                .environmentLocation(environment == null ? null : environment.location())
                .environmentTimeOfDay(environment == null ? null : environment.timeOfDay())
                .environmentWeather(environment == null ? null : environment.weather())
                .environmentEffects(environment == null ? null : environment.environmentalEffects())
                .lightingKeyLightNote(lighting == null ? null : lighting.keyLightNote())
                .lightingMood(lighting == null ? null : lighting.mood())
                .productIsHeroShot(productBrand == null ? null : productBrand.isProductHeroShot())
                .productPlacement(productBrand == null ? null : productBrand.productPlacement())
                .productRefBucket(productBrand == null ? null : productBrand.productRefBucket())
                .productRefObjectKey(productBrand == null ? null : productBrand.productRefObjectKey())
                .technicalDurationSeconds(technical == null ? null : technical.durationSeconds())
                .technicalAspectRatio(technical == null ? null : technical.aspectRatio())
                .technicalTargetProvider(technical == null ? null : technical.targetProvider())
                .technicalTargetModel(technical == null ? null : technical.targetModel())
                .audioAmbientDescription(audioAmbience == null ? null : audioAmbience.ambientDescription())
                .audioMusicMoodNote(audioAmbience == null ? null : audioAmbience.musicMoodNote())
                .createdAt(OffsetDateTime.now());
        CameraSnapshotMapper.applyTo(builder, camera);
        snapshotRepository.save(builder.build());

        if (plan.characters() != null) {
            plan.characters().forEach(c -> characterRepository.save(CritiquePlanCharacter.builder()
                    .snapshotId(snapshotId)
                    .castId(c.castId())
                    .faceRefBucket(c.faceRefBucket())
                    .faceRefObjectKey(c.faceRefObjectKey())
                    .wardrobeNote(c.wardrobeNote())
                    .performanceDirection(c.performanceDirection())
                    .build()));
        }
        if (plan.continuityAnchors() != null) {
            plan.continuityAnchors().forEach(a -> continuityAnchorRepository.save(CritiquePlanContinuityAnchor.builder()
                    .snapshotId(snapshotId)
                    .anchorType(a.anchorType())
                    .subjectId(a.subjectId())
                    .description(a.description())
                    .referenceObjectKey(a.referenceObjectKey())
                    .build()));
        }
    }

    public Optional<ShotContext> load(UUID sessionId, PlanSnapshotKind kind) {
        return snapshotRepository.findBySessionIdAndKind(sessionId, kind).map(this::toShotContext);
    }

    private ShotContext toShotContext(CritiquePlanSnapshot s) {
        List<Character> characters = characterRepository.findBySnapshotId(s.getId()).stream()
                .map(c -> new Character(c.getCastId(), c.getFaceRefBucket(), c.getFaceRefObjectKey(), c.getWardrobeNote(), c.getPerformanceDirection()))
                .collect(Collectors.toList());
        List<ContinuityAnchor> anchors = continuityAnchorRepository.findBySnapshotId(s.getId()).stream()
                .map(a -> new ContinuityAnchor(a.getAnchorType(), a.getSubjectId(), a.getDescription(), a.getReferenceObjectKey()))
                .collect(Collectors.toList());

        return new ShotContext(
                s.getShotRef(),
                new Narrative(s.getNarrativeScriptLine(), s.getNarrativeScreenplaySlug(), s.getNarrativeArcPosition()),
                characters,
                new Environment(s.getEnvironmentLocation(), s.getEnvironmentTimeOfDay(), s.getEnvironmentWeather(), s.getEnvironmentEffects()),
                new Lighting(s.getLightingKeyLightNote(), s.getLightingMood()),
                CameraSnapshotMapper.fromSnapshot(s),
                new ProductBrand(s.getProductIsHeroShot(), s.getProductPlacement(), s.getProductRefBucket(), s.getProductRefObjectKey()),
                new Technical(s.getTechnicalDurationSeconds(), s.getTechnicalAspectRatio(), s.getTechnicalTargetProvider(), s.getTechnicalTargetModel()),
                anchors,
                new AudioAmbience(s.getAudioAmbientDescription(), s.getAudioMusicMoodNote()));
    }
}
