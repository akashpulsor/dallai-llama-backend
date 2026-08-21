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

@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "model_supported_language")
public class ModelSupportedLanguage {

    @EmbeddedId
    @AttributeOverrides({
            @AttributeOverride(name = "modelId", column = @Column(name = "model_id")),
            @AttributeOverride(name = "languageCode", column = @Column(name = "language_code"))
    })
    private Id id;

    @Getter
    @Setter
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Id implements Serializable {
        private String modelId;
        private String languageCode;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof Id id)) return false;
            return Objects.equals(modelId, id.modelId) && Objects.equals(languageCode, id.languageCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(modelId, languageCode);
        }
    }
}
