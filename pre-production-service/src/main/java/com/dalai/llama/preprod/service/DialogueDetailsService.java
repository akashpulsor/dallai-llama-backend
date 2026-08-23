package com.dalai.llama.preprod.service;

import com.dalai.llama.preprod.domain.entity.CastAssignment;
import com.dalai.llama.preprod.domain.entity.CastProfile;
import com.dalai.llama.preprod.domain.entity.Script;
import com.dalai.llama.preprod.domain.entity.ScriptCharacter;
import com.dalai.llama.preprod.domain.entity.Shot;
import com.dalai.llama.preprod.dto.ShotDialogueView;
import com.dalai.llama.preprod.repository.CastAssignmentRepository;
import com.dalai.llama.preprod.repository.CastProfileRepository;
import com.dalai.llama.preprod.repository.ScriptCharacterRepository;
import com.dalai.llama.preprod.repository.ScriptRepository;
import com.dalai.llama.preprod.repository.ShotRepository;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Serves post-production-service's {@code GET /v1/projects/{projectId}/shots/{shotRef}/dialogue}
 * contract -- the endpoint {@code HttpPreProductionClient} has been calling against a service that
 * "does not exist in the cluster yet" (its own javadoc). This closes that gap: dialogue text, the
 * language it was written in, and a signed URL to the assigned cast member's voice sample, all
 * resolved from data that already exists (Shot.scriptLine/voiceOver, ProjectConfig.dialogueLanguage,
 * CastAssignment -> CastProfile.voiceRefBucket/voiceRefObjectKey) -- no new storage, just wiring. */
@Service
public class DialogueDetailsService {

    private final ShotRepository shotRepository;
    private final ScriptRepository scriptRepository;
    private final ScriptCharacterRepository scriptCharacterRepository;
    private final CastAssignmentRepository castAssignmentRepository;
    private final CastProfileRepository castProfileRepository;
    private final ProjectConfigService projectConfigService;
    private final MinioClient minioClient;

    public DialogueDetailsService(
            ShotRepository shotRepository,
            ScriptRepository scriptRepository,
            ScriptCharacterRepository scriptCharacterRepository,
            CastAssignmentRepository castAssignmentRepository,
            CastProfileRepository castProfileRepository,
            ProjectConfigService projectConfigService,
            MinioClient minioClient
    ) {
        this.shotRepository = shotRepository;
        this.scriptRepository = scriptRepository;
        this.scriptCharacterRepository = scriptCharacterRepository;
        this.castAssignmentRepository = castAssignmentRepository;
        this.castProfileRepository = castProfileRepository;
        this.projectConfigService = projectConfigService;
        this.minioClient = minioClient;
    }

    @Transactional(readOnly = true)
    public ShotDialogueView getShotDialogue(UUID tenantId, UUID projectId, String shotRef) {
        Shot shot = shotRepository.findByProjectIdAndShotRef(projectId, shotRef)
                .orElseThrow(() -> PreProductionException.notFound("No shot " + shotRef + " in project " + projectId));

        var config = projectConfigService.getEntityOrDefault(projectId);
        String language = (config == null || config.getDialogueLanguage() == null || config.getDialogueLanguage().isBlank())
                ? "en-US" : config.getDialogueLanguage();

        Script script = scriptRepository.findByProjectId(projectId).orElse(null);
        String dialogueScript = shot.getScriptLine() != null && !shot.getScriptLine().isBlank()
                ? shot.getScriptLine() : shot.getVoiceOver();

        String characterName = shot.getPrimaryCharacterKey();
        String referenceAudioUrl = null;
        if (script != null && shot.getPrimaryCharacterKey() != null) {
            Optional<ScriptCharacter> character = scriptCharacterRepository
                    .findByScriptIdAndCharacterKey(script.getId(), shot.getPrimaryCharacterKey());
            if (character.isPresent()) {
                characterName = character.get().getCharacterName();
                Optional<CastAssignment> assignment = castAssignmentRepository
                        .findByProjectIdAndScriptCharacterId(projectId, character.get().getId());
                if (assignment.isPresent()) {
                    Optional<CastProfile> profile = castProfileRepository
                            .findByIdAndTenantId(assignment.get().getCastProfileId(), tenantId);
                    if (profile.isPresent() && profile.get().getVoiceRefBucket() != null
                            && profile.get().getVoiceRefObjectKey() != null) {
                        referenceAudioUrl = signedUrl(profile.get().getVoiceRefBucket(), profile.get().getVoiceRefObjectKey());
                    }
                }
            }
        }

        return new ShotDialogueView(projectId, script == null ? null : script.getId(), shotRef, shot.getShotNumber(),
                characterName, dialogueScript, language, language, referenceAudioUrl);
    }

    private String signedUrl(String sourceBucket, String objectKey) {
        try {
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(sourceBucket)
                    .object(objectKey)
                    .expiry(1, TimeUnit.HOURS)
                    .build());
        } catch (Exception ex) {
            throw PreProductionException.upstream("Could not sign voice reference URL: " + ex.getMessage());
        }
    }
}
