package com.dalai.llama.tenant.showcase.repository;

import com.dalai.llama.tenant.showcase.domain.entity.CreatorHandleHistory;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;

public interface CreatorHandleHistoryRepository extends JpaRepository<CreatorHandleHistory, String> {

    /** True while the handle is still reserved for the creator who released it. */
    boolean existsByHandleAndReleasedAtAfter(String handle, OffsetDateTime cutoff);
}
