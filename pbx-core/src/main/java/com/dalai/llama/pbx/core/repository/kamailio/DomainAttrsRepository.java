package com.dalai.llama.pbx.core.repository.kamailio;


import com.dalai.llama.pbx.core.domain.entity.kamailio.DomainAttrs;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Kamailio domain_attrs table.
 * Key-value attributes per domain (e.g., caller-id override, domain flags).
 * Kamailio reads via domain module. PBX-Core writes during provisioning.
 */
@Repository
public interface DomainAttrsRepository extends JpaRepository<DomainAttrs, Integer> {

    List<DomainAttrs> findByDid(String did);

    List<DomainAttrs> findByDidAndName(String did, String name);

    @Modifying
    void deleteByDid(String did);
}