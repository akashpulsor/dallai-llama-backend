package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.Intent;
import org.springframework.boot.crm.entity.PortalConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;

public interface IntentRepository  extends JpaRepository<Intent, Integer> {
    // Find intent by ID with portal configuration and child intents eagerly loaded
    @Query("SELECT i FROM Intent i " +
            "LEFT JOIN FETCH i.portalConfiguration " +
            "LEFT JOIN FETCH i.childIntents " +
            "WHERE i.intentId = :intentId")
    Optional<Intent> findByIdWithPortalAndChildIntents(Integer intentId);

    // Find intents by portal configuration
    List<Intent> findByPortalConfiguration(PortalConfiguration portalConfiguration);
}
