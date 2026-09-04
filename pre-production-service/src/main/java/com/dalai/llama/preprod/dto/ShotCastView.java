package com.dalai.llama.preprod.dto;

import com.dalai.llama.preprod.domain.CharacterType;

import java.util.UUID;

/** Who's actually in this shot, resolved all the way from Shot.primaryCharacterKey through
 * ScriptCharacter to the CastAssignment's CastProfile -- the face/voice a viewer of the shot list
 * should see inherited from the real actor cast for this character, not just the character's
 * narrative key. Null when the shot has no primaryCharacterKey, the character has no CastAssignment
 * yet, or the character is a NARRATOR (never in frame). */
public record ShotCastView(
        String characterKey,
        String characterName,
        CharacterType characterType,
        UUID castProfileId,
        String castDisplayName,
        String castFaceImageUrl,
        boolean hasVoiceSample
) {
}
