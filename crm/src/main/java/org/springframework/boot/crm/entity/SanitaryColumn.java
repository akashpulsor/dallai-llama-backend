package org.springframework.boot.crm.entity;

import jakarta.persistence.Embeddable;
import jakarta.persistence.EntityListeners;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Data
@Embeddable
@EntityListeners(AuditingEntityListener.class)
public class SanitaryColumn {
    @CreatedDate
    private LocalDateTime createdAt;

    @LastModifiedDate
    private LocalDateTime updatedAt;
}
