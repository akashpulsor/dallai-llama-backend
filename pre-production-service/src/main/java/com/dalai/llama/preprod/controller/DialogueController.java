package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.ShotDialogueView;
import com.dalai.llama.preprod.service.DialogueDetailsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** Serves post-production-service's DialogueSyncCoordinator (voice-clone/dubbing/lip-sync
 * pipeline) -- see {@code HttpPreProductionClient}'s contract javadoc on that service's side. */
@RestController
public class DialogueController extends BaseController {

    private final DialogueDetailsService dialogueDetailsService;

    public DialogueController(DialogueDetailsService dialogueDetailsService) {
        this.dialogueDetailsService = dialogueDetailsService;
    }

    @GetMapping("/v1/projects/{projectId}/shots/{shotRef}/dialogue")
    public ResponseEntity<ShotDialogueView> getShotDialogue(@PathVariable UUID projectId, @PathVariable String shotRef) {
        return ResponseEntity.ok(dialogueDetailsService.getShotDialogue(tenant().tenantId(), projectId, shotRef));
    }
}
