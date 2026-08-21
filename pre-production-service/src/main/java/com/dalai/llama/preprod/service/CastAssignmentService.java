package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.dto.CastAssignmentView;
import com.dalai.llama.preprod.dto.CreateCastAssignmentRequest;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class CastAssignmentService {

    private final CastAssignmentRepository castAssignmentRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final CastProfileService castProfileService;

    public CastAssignmentService(
            CastAssignmentRepository castAssignmentRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            CastProfileService castProfileService
    ) {
        this.castAssignmentRepository = castAssignmentRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.castProfileService = castProfileService;
    }

    @Transactional
    public CastAssignmentView assign(UUID tenantId, UUID projectId, CreateCastAssignmentRequest request) {
        ScriptCharacter character = scriptCharacterRepository.findById(request.scriptCharacterId())
                .orElseThrow(() -> PreProductionException.notFound("No script character " + request.scriptCharacterId()));
        CastProfile profile = castProfileService.requireCastProfile(tenantId, request.castProfileId());

        OffsetDateTime now = OffsetDateTime.now();
        CastAssignment assignment = castAssignmentRepository.findByProjectIdAndScriptCharacterId(projectId, character.getId())
                .orElseGet(() -> CastAssignment.builder()
                        .tenantId(tenantId)
                        .projectId(projectId)
                        .scriptCharacterId(character.getId())
                        .createdAt(now)
                        .build());
        assignment.setCastProfileId(profile.getId());
        assignment.setWardrobeNote(request.wardrobeNote());
        assignment.setPerformanceDirection(request.performanceDirection());
        assignment.setUpdatedAt(now);
        return toView(castAssignmentRepository.save(assignment));
    }

    @Transactional(readOnly = true)
    public List<CastAssignmentView> list(UUID projectId) {
        return castAssignmentRepository.findByProjectId(projectId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    private CastAssignmentView toView(CastAssignment assignment) {
        return new CastAssignmentView(assignment.getId(), assignment.getScriptCharacterId(),
                assignment.getCastProfileId(), assignment.getWardrobeNote(), assignment.getPerformanceDirection());
    }
}
