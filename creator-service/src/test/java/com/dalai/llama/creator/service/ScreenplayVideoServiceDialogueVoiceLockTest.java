package com.dalai.llama.creator.service;

import com.dalai.llama.creator.repository.CreatorGenerationJobRepository;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Answers.CALLS_REAL_METHODS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

/**
 * Characterization test for the confirmed fix: decideSceneDialogueVoice() used to take no
 * advisory lock at all, while its two siblings (generateSceneDialogueVoice, combineSceneDialogueAudio)
 * both lock "screenplay-scene-voice:{tenant}:{user}:{runId}" - a real race window against a
 * concurrent generate/combine call on the same scene. This pins that the lock is now acquired
 * with the identical key, and that a 409 is thrown (before touching the run/scene at all) when
 * it can't be.
 */
class ScreenplayVideoServiceDialogueVoiceLockTest {

    @Test
    void decideSceneDialogueVoice_acquiresSameLockKeyAsItsSiblingsAndRejectsWhenUnavailable() throws Exception {
        ScreenplayVideoService service = mock(
                ScreenplayVideoService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS)
        );
        CreatorGenerationJobRepository generationJobRepository = mock(CreatorGenerationJobRepository.class);
        setField(service, "generationJobRepository", generationJobRepository);

        UUID runId = UUID.randomUUID();
        String expectedLockKey = "screenplay-scene-voice:tenant-1:user-1:" + runId;
        when(generationJobRepository.tryAcquireTransactionalAdvisoryLock(eq(expectedLockKey))).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.decideSceneDialogueVoice(runId, "scene-1", Map.of(), "tenant-1", "user-1")
        );

        assertEquals(409, ex.getStatusCode().value());
        verify(generationJobRepository).tryAcquireTransactionalAdvisoryLock(expectedLockKey);
    }

    // generateSceneDialogueVoice and combineSceneDialogueAudio had zero test coverage before the
    // DialogueVoiceCloner extraction moved them out of ScreenplayVideoService - these two pin the
    // same lock-key contract the sibling test above already covers for decideSceneDialogueVoice,
    // now routed through ScreenplayVideoService.dialogueVoiceCloner() -> DialogueVoiceCloner.

    @Test
    void generateSceneDialogueVoice_acquiresSameLockKeyAsItsSiblingsAndRejectsWhenUnavailable() throws Exception {
        ScreenplayVideoService service = mock(
                ScreenplayVideoService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS)
        );
        CreatorGenerationJobRepository generationJobRepository = mock(CreatorGenerationJobRepository.class);
        setField(service, "generationJobRepository", generationJobRepository);

        UUID runId = UUID.randomUUID();
        String expectedLockKey = "screenplay-scene-voice:tenant-1:user-1:" + runId;
        when(generationJobRepository.tryAcquireTransactionalAdvisoryLock(eq(expectedLockKey))).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.generateSceneDialogueVoice(runId, "scene-1", Map.of(), "tenant-1", "user-1")
        );

        assertEquals(409, ex.getStatusCode().value());
        verify(generationJobRepository).tryAcquireTransactionalAdvisoryLock(expectedLockKey);
    }

    @Test
    void combineSceneDialogueAudio_acquiresSameLockKeyAsItsSiblingsAndRejectsWhenUnavailable() throws Exception {
        ScreenplayVideoService service = mock(
                ScreenplayVideoService.class,
                withSettings().defaultAnswer(CALLS_REAL_METHODS)
        );
        CreatorGenerationJobRepository generationJobRepository = mock(CreatorGenerationJobRepository.class);
        setField(service, "generationJobRepository", generationJobRepository);

        UUID runId = UUID.randomUUID();
        String expectedLockKey = "screenplay-scene-voice:tenant-1:user-1:" + runId;
        when(generationJobRepository.tryAcquireTransactionalAdvisoryLock(eq(expectedLockKey))).thenReturn(false);

        ResponseStatusException ex = assertThrows(ResponseStatusException.class, () ->
                service.combineSceneDialogueAudio(runId, "tenant-1", "user-1")
        );

        assertEquals(409, ex.getStatusCode().value());
        verify(generationJobRepository).tryAcquireTransactionalAdvisoryLock(expectedLockKey);
    }

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = ScreenplayVideoService.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }
}
