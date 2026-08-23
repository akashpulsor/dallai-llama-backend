package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.ContinuityLockCategory;
import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.ContinuityBible;
import com.dalai.llama.preprod.domain.entity.ContinuityLock;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.ContinuityBibleView;
import com.dalai.llama.preprod.dto.ContinuityBibleView.ContinuityLockView;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.ContinuityBibleRepository;
import com.dalai.llama.preprod.repository.ContinuityLockRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Deterministically recomputes a project's {@link ContinuityBible} -- never a fresh LLM call,
 * same "Java-computed, not the model's judgment" philosophy creator-service's real
 * videoConsistencyBible used. Deduped, capped at 8 values per category. Called once at the end
 * of shot-list generation (the point at which every shot's fields are known), not on every
 * dispatch.
 */
@Service
public class ContinuityBibleService {

    private static final int MAX_LOCKS_PER_CATEGORY = 8;
    private static final String DEFAULT_NEGATIVE_PROMPT =
            "No continuity breaks: keep character identity, wardrobe/appearance, set and props, "
                    + "camera language, and lighting consistent with the locks above unless a shot explicitly calls for a deliberate change.";

    private final ContinuityBibleRepository continuityBibleRepository;
    private final ContinuityLockRepository continuityLockRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final CastAssignmentRepository castAssignmentRepository;
    private final ShotRepository shotRepository;

    public ContinuityBibleService(
            ContinuityBibleRepository continuityBibleRepository,
            ContinuityLockRepository continuityLockRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            CastAssignmentRepository castAssignmentRepository,
            ShotRepository shotRepository
    ) {
        this.continuityBibleRepository = continuityBibleRepository;
        this.continuityLockRepository = continuityLockRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.shotRepository = shotRepository;
    }

    @Transactional
    public void refresh(UUID tenantId, UUID projectId) {
        OffsetDateTime now = OffsetDateTime.now();
        ContinuityBible bible = continuityBibleRepository.findByProjectId(projectId).orElseGet(() -> ContinuityBible.builder()
                .tenantId(tenantId)
                .projectId(projectId)
                .negativePrompt(DEFAULT_NEGATIVE_PROMPT)
                .createdAt(now)
                .build());
        bible.setUpdatedAt(now);
        bible = continuityBibleRepository.save(bible);
        continuityLockRepository.deleteByContinuityBibleId(bible.getId());

        List<Shot> shots = shotRepository.findByProjectIdOrderByShotNumberAsc(projectId);
        List<CastAssignment> assignments = castAssignmentRepository.findByProjectId(projectId);

        saveLocks(tenantId, bible.getId(), ContinuityLockCategory.CHARACTER_IDENTITY, characterIdentityValues(projectId), now);
        saveLocks(tenantId, bible.getId(), ContinuityLockCategory.WARDROBE_APPEARANCE, wardrobeValues(assignments), now);
        saveLocks(tenantId, bible.getId(), ContinuityLockCategory.SET_PROP, dedupField(shots, s -> s.getLocation()), now);
        saveLocks(tenantId, bible.getId(), ContinuityLockCategory.CAMERA_LANGUAGE, dedupField(shots, s -> s.getCameraMovement()), now);
        saveLocks(tenantId, bible.getId(), ContinuityLockCategory.LIGHTING_COLOR,
                dedupField(shots, s -> s.getLightingMood() == null ? null : s.getLightingMood().toString()), now);
    }

    @Transactional(readOnly = true)
    public List<ContinuityLock> getLocks(UUID projectId) {
        return continuityBibleRepository.findByProjectId(projectId)
                .map(bible -> continuityLockRepository.findByContinuityBibleId(bible.getId()))
                .orElse(List.of());
    }

    @Transactional(readOnly = true)
    public ContinuityBibleView getView(UUID tenantId, UUID projectId) {
        ContinuityBible bible = continuityBibleRepository.findByProjectId(projectId)
                .filter(b -> b.getTenantId().equals(tenantId))
                .orElseThrow(() -> PreProductionException.notFound("No continuity bible for project " + projectId + " yet -- generate the shot list first"));
        List<ContinuityLockView> locks = continuityLockRepository.findByContinuityBibleId(bible.getId()).stream()
                .map(lock -> new ContinuityLockView(lock.getCategory().toString(), lock.getValue()))
                .collect(Collectors.toList());
        return new ContinuityBibleView(bible.getNegativePrompt(), locks);
    }

    private List<String> characterIdentityValues(UUID projectId) {
        Script script = scriptRepository.findByProjectId(projectId).orElse(null);
        if (script == null) {
            return List.of();
        }
        return scriptCharacterRepository.findByScriptId(script.getId()).stream()
                .map(ScriptCharacter::getVisualIdentity)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .limit(MAX_LOCKS_PER_CATEGORY)
                .collect(Collectors.toList());
    }

    private List<String> wardrobeValues(List<CastAssignment> assignments) {
        return assignments.stream()
                .map(CastAssignment::getWardrobeNote)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .limit(MAX_LOCKS_PER_CATEGORY)
                .collect(Collectors.toList());
    }

    private List<String> dedupField(List<Shot> shots, java.util.function.Function<Shot, String> extractor) {
        return shots.stream()
                .map(extractor)
                .filter(v -> v != null && !v.isBlank())
                .distinct()
                .limit(MAX_LOCKS_PER_CATEGORY)
                .collect(Collectors.toList());
    }

    private void saveLocks(UUID tenantId, UUID bibleId, ContinuityLockCategory category, List<String> values, OffsetDateTime now) {
        values.forEach(value -> continuityLockRepository.save(ContinuityLock.builder()
                .tenantId(tenantId)
                .continuityBibleId(bibleId)
                .category(category)
                .value(value)
                .createdAt(now)
                .build()));
    }
}
