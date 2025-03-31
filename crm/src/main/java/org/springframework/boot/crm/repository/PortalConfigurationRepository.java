package org.springframework.boot.crm.repository;

import org.springframework.boot.crm.entity.Intent;
import org.springframework.boot.crm.entity.PortalConfiguration;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface PortalConfigurationRepository extends JpaRepository<PortalConfiguration, Integer> {
    // Find portal configuration by name with intents eagerly loaded
    @Query("SELECT pc FROM PortalConfiguration pc LEFT JOIN FETCH pc.intents WHERE pc.portalName = :portalName")
    Optional<PortalConfiguration> findByPortalNameWithIntents(String portalName);

    // Find portal configuration by ID with intents eagerly loaded
    @Query("SELECT pc FROM PortalConfiguration pc LEFT JOIN FETCH pc.intents WHERE pc.portalId = :portalId")
    Optional<PortalConfiguration> findByIdWithIntents(Integer portalId);


    List<PortalConfiguration> findByBusinessId(@Param("businessId") int businessId);
}
