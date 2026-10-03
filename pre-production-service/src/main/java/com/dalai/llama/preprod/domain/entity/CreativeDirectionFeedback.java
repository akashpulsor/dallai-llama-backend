package com.dalai.llama.preprod.domain.entity;

import com.dalai.llama.preprod.domain.ReviewActor;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.OffsetDateTime;
import java.util.UUID;

/** A review note on one treatment, from the creator or from the client. See V73. */
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "creative_direction_feedback")
public class CreativeDirectionFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    @Column(name = "creative_direction_id", nullable = false)
    private UUID creativeDirectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 16)
    private ReviewActor source;

    @Column(name = "author_user_id")
    private UUID authorUserId;

    @Column(name = "feedback", nullable = false, columnDefinition = "text")
    private String feedback;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
