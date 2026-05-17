package com.dalai.llama.creator.repository;

import com.dalai.llama.creator.domain.ConnectorRunStatus;
import com.dalai.llama.creator.domain.entity.CreatorConnectorRun;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.UUID;

public interface CreatorConnectorRunRepository extends JpaRepository<CreatorConnectorRun, UUID> {

    long countByConnectorCodeAndStatusAndStartedAtAfter(
            String connectorCode,
            ConnectorRunStatus status,
            OffsetDateTime startedAfter
    );

    long countByConnectorCodeAndStartedAtAfter(String connectorCode, OffsetDateTime startedAfter);
}
