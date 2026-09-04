package com.dalai.llama.creativeplanning.service.requirement;

import com.dalai.llama.creativeplanning.domain.BudgetTier;
import com.dalai.llama.creativeplanning.domain.entity.LockedIdea;
import com.dalai.llama.creativeplanning.dto.LockIdeaOptionRequest;
import com.dalai.llama.creativeplanning.repository.LockedIdeaRepository;
import com.dalai.llama.creativeplanning.service.CreativePlanningException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Splits {@code ProjectRequirementIdeaService.lockOption}'s two writes into their own
 * transactions so a downstream pre-production-service failure can never roll back a
 * {@link LockedIdea} row that already committed. Each method here is a separate, real Spring
 * proxy boundary (a different bean, called from outside itself) -- if the LockedIdea insert and
 * the pre-production HTTP call shared one {@code @Transactional} method, an exception thrown
 * after pre-production-service had already created its Project (a timeout, a network blip) would
 * mark that ambient transaction rollback-only and undo the LockedIdea insert too, so a retry's
 * idempotency check (find-by-requirement) would find nothing and mint a second LockedIdea plus a
 * second, orphaned Project. With the insert committed independently, a retry always finds the
 * existing row (see {@link #findOrCreate}) and, at worst, only re-attempts the HTTP call --
 * which pre-production-service's own {@code ProjectService.createFromLockedIdea} is idempotent
 * by lockedIdeaId for anyway. */
@Service
public class LockedIdeaWriter {

    private final LockedIdeaRepository lockedIdeaRepository;

    public LockedIdeaWriter(LockedIdeaRepository lockedIdeaRepository) {
        this.lockedIdeaRepository = lockedIdeaRepository;
    }

    @Transactional
    public LockedIdea findOrCreate(UUID tenantId, UUID requirementId, LockIdeaOptionRequest chosen, BudgetTier budgetTier) {
        return lockedIdeaRepository.findByProjectRequirementId(requirementId)
                .orElseGet(() -> lockedIdeaRepository.save(LockedIdea.builder()
                        .tenantId(tenantId)
                        .projectRequirementId(requirementId)
                        .title(chosen.title())
                        .concept(chosen.concept())
                        .targetAudience(chosen.targetAudience())
                        .campaignAngle(chosen.campaignAngle())
                        .keyMessage(chosen.keyMessage())
                        .tone(chosen.tone())
                        .budgetTier(budgetTier)
                        .createdAt(OffsetDateTime.now())
                        .build()));
    }

    @Transactional
    public void attachProject(UUID lockedIdeaId, UUID projectId) {
        LockedIdea lockedIdea = lockedIdeaRepository.findById(lockedIdeaId)
                .orElseThrow(() -> CreativePlanningException.conflict("Locked idea " + lockedIdeaId + " disappeared before its project could be attached"));
        lockedIdea.setProjectId(projectId);
        lockedIdeaRepository.save(lockedIdea);
    }
}
