package com.dalai.llama.llmgateway.domain.entity;

import jakarta.persistence.AttributeOverride;
import jakarta.persistence.AttributeOverrides;
import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.Objects;

/** Many-to-many, same shape as {@link ModelSupportedLanguage} -- one stock voice can speak several
 * languages (ElevenLabs' multilingual voices aren't language-specific), so this is a plain join
 * table rather than a column on {@link BuiltinVoice}. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "builtin_voice_language")
public class BuiltinVoiceLanguage {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "voiceId", column = @Column(name = "voice_id")),
            @AttributeOverride(name = "languageCode", column = @Column(name = "language_code"))
    })
    private Id id;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        private String voiceId;
        private String languageCode;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id id)) return false;
            return Objects.equals(voiceId, id.voiceId) && Objects.equals(languageCode, id.languageCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(voiceId, languageCode);
        }
    }
}
