package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.CastProfileType;
import com.dalai.llama.preprod.domain.CharacterType;
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
        requireCompatibleTypes(character, profile);

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

    /** The two profileType/characterType enums only genuinely disagree on "a person" (frontend's
     * {@code CastSection.jsx} maps both HUMAN and NARRATOR characters to an ACTOR profile -- see
     * its {@code toProfileType} comment -- there's no dedicated NARRATOR-profile requirement in
     * practice). So the one rule actually worth enforcing here is PRODUCT-ness matching: a PRODUCT
     * character needs a PRODUCT profile (the showcased item), and a HUMAN/NARRATOR character needs
     * a non-PRODUCT profile (someone who can speak) -- catches the same class of mismatch as the
     * voice-on-a-PRODUCT-profile gap this change also closes in {@code CastProfileService}. */
    private void requireCompatibleTypes(ScriptCharacter character, CastProfile profile) {
        boolean characterIsProduct = character.getCharacterType() == CharacterType.PRODUCT;
        boolean profileIsProduct = profile.getProfileType() == CastProfileType.PRODUCT;
        if (characterIsProduct != profileIsProduct) {
            throw PreProductionException.badRequest("Cannot assign a " + profile.getProfileType()
                    + " profile to a " + character.getCharacterType() + " character");
        }
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
