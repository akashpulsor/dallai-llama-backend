package com.dalai.llama.videogen.dto.shotcontext;

import com.dalai.llama.videogen.domain.EmotionalArcPosition;

public record Narrative(
        String scriptLine,
        String screenplaySlug,
        EmotionalArcPosition arcPosition,
        /** What the character actually says, as its own field.
         *
         * <p>The prompt strategies used {@link #scriptLine} for BOTH the "Action:" line and the
         * "Dialogue/VO:" line, so every shot's action and dialogue were the same sentence
         * repeated -- and the shot's own {@code voiceOver} column, which is what the creator edits
         * when they change the spoken line, reached neither. Editing the voice-over changed
         * nothing in the prompt. */
        String dialogue
) {

    /** Pre-dialogue arity, kept so existing callers and tests compile unchanged. */
    public Narrative(String scriptLine, String screenplaySlug, EmotionalArcPosition arcPosition) {
        this(scriptLine, screenplaySlug, arcPosition, null);
    }
}
