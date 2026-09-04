package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CharacterType;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.domain.entity.ShotDialogueBeat;
import com.dalai.llama.preprod.dto.SaveShotDialogueBeatRequest;
import com.dalai.llama.preprod.dto.ShotDialogueBeatView;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotDialogueBeatRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/** CRUD for {@link ShotDialogueBeat} -- the creator plans a shot's dialogue timing here before
 * dispatch; {@code ShotContextAssemblyService} reads it back at dispatch time. */
@Service
public class ShotDialogueBeatService {

    private final ShotRepository shotRepository;
    private final ShotDialogueBeatRepository shotDialogueBeatRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;

    public ShotDialogueBeatService(
            ShotRepository shotRepository,
            ShotDialogueBeatRepository shotDialogueBeatRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository
    ) {
        this.shotRepository = shotRepository;
        this.shotDialogueBeatRepository = shotDialogueBeatRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
    }

    @Transactional(readOnly = true)
    public List<ShotDialogueBeatView> list(UUID tenantId, UUID shotId) {
        requireShot(tenantId, shotId);
        return shotDialogueBeatRepository.findByShotIdOrderByOrderIndexAsc(shotId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    @Transactional
    public ShotDialogueBeatView create(UUID tenantId, UUID shotId, SaveShotDialogueBeatRequest request) {
        Shot shot = requireShot(tenantId, shotId);
        OffsetDateTime now = OffsetDateTime.now();
        ShotDialogueBeat beat = shotDialogueBeatRepository.save(ShotDialogueBeat.builder()
                .tenantId(tenantId)
                .shotId(shotId)
                .orderIndex(request.orderIndex())
                .startSeconds(request.startSeconds())
                .durationSeconds(resolveDurationSeconds(request, shot))
                .text(resolveText(request, shot))
                .characterKey(resolveCharacterKey(request, shot))
                .createdAt(now)
                .updatedAt(now)
                .build());
        return toView(beat);
    }

    @Transactional
    public ShotDialogueBeatView update(UUID tenantId, UUID shotId, UUID beatId, SaveShotDialogueBeatRequest request) {
        Shot shot = requireShot(tenantId, shotId);
        ShotDialogueBeat beat = shotDialogueBeatRepository.findByIdAndTenantId(beatId, tenantId)
                .filter(b -> b.getShotId().equals(shotId))
                .orElseThrow(() -> PreProductionException.notFound("No dialogue beat " + beatId + " for shot " + shotId));
        beat.setOrderIndex(request.orderIndex());
        beat.setStartSeconds(request.startSeconds());
        beat.setDurationSeconds(resolveDurationSeconds(request, shot));
        beat.setText(resolveText(request, shot));
        beat.setCharacterKey(resolveCharacterKey(request, shot));
        beat.setUpdatedAt(OffsetDateTime.now());
        return toView(shotDialogueBeatRepository.save(beat));
    }

    @Transactional
    public void delete(UUID tenantId, UUID shotId, UUID beatId) {
        ShotDialogueBeat beat = shotDialogueBeatRepository.findByIdAndTenantId(beatId, tenantId)
                .filter(b -> b.getShotId().equals(shotId))
                .orElseThrow(() -> PreProductionException.notFound("No dialogue beat " + beatId + " for shot " + shotId));
        shotDialogueBeatRepository.delete(beat);
    }

    private Shot requireShot(UUID tenantId, UUID shotId) {
        return shotRepository.findByIdAndTenantId(shotId, tenantId)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotId));
    }

    /** Null/blank means "clone the shot's own planned line" -- text is never re-derived from what
     * the UI happened to hold, only from the Shot row itself. A non-blank value is a genuine
     * one-off override; since it's never written back to {@code Shot.scriptLine}, the shot's plan
     * remains untouched and is the rollback target if the override is ever removed.
     * {@code voiceOver} is the actual line to be spoken; {@code scriptLine} is the shot's creative
     * brief/purpose, not dialogue -- same priority {@link DialogueDetailsService} uses. */
    private String resolveText(SaveShotDialogueBeatRequest request, Shot shot) {
        if (request.text() != null && !request.text().isBlank()) {
            return request.text();
        }
        return shot.getVoiceOver() != null && !shot.getVoiceOver().isBlank() ? shot.getVoiceOver() : shot.getScriptLine();
    }

    /** No one on screen but there's still a line to speak -- the narrator (never in
     * {@code primaryCharacterKey}, per {@link CharacterType}'s own javadoc) is who's actually
     * talking, so beat-dubbing resolves its voice sample the same way {@link DialogueDetailsService}
     * does for the read-only dialogue view. */
    private String resolveCharacterKey(SaveShotDialogueBeatRequest request, Shot shot) {
        if (request.characterKey() != null) {
            return request.characterKey();
        }
        if (shot.getPrimaryCharacterKey() != null) {
            return shot.getPrimaryCharacterKey();
        }
        return scriptRepository.findByProjectId(shot.getProjectId())
                .flatMap(script -> scriptCharacterRepository.findFirstByScriptIdAndCharacterType(script.getId(), CharacterType.NARRATOR))
                .map(character -> character.getCharacterKey())
                .orElse(null);
    }

    private BigDecimal resolveDurationSeconds(SaveShotDialogueBeatRequest request, Shot shot) {
        if (request.durationSeconds() != null) {
            return request.durationSeconds();
        }
        return shot.getDurationSeconds() != null ? BigDecimal.valueOf(shot.getDurationSeconds()) : BigDecimal.valueOf(4);
    }

    private ShotDialogueBeatView toView(ShotDialogueBeat beat) {
        return new ShotDialogueBeatView(beat.getId(), beat.getOrderIndex(), beat.getStartSeconds(),
                beat.getDurationSeconds(), beat.getText(), beat.getCharacterKey());
    }
}
