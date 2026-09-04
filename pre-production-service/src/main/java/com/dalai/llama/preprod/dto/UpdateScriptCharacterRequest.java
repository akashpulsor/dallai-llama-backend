package com.dalai.llama.preprod.dto;

/** Every field optional -- only what's provided gets updated, same convention as
 * UpdateProjectConfigRequest. Lets a creator correct/refine what generation produced (a wrong
 * age, a name spelling, added backstory detail) without regenerating the whole script. */
public record UpdateScriptCharacterRequest(
        String characterName,
        String characterRole,
        String description,
        String characterType,
        String gender,
        Integer age,
        String ageRange,
        String look,
        String complexion,
        String profile,
        String persona,
        String backstory,
        String motivation,
        String fearOrBlock,
        String relationshipToStory,
        String speakingStyle,
        String visualIdentity
) {
}
