package com.dalai.llama.pbx.core.repository.kamailio;


import com.dalai.llama.pbx.core.domain.entity.kamailio.Dialplan;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

/**
 * Kamailio dialplan table — number manipulation / translation rules.
 *
 * Used by Kamailio dp_translate() for E.164 normalization, extension mapping, etc.
 * NOT the same as tenant_dialplan (which stores FreeSWITCH XML for mod_xml_curl).
 *
 * dpid groups rules by purpose (e.g., dpid=1 for inbound normalization,
 * dpid=2 for outbound formatting). Rules within a dpid are ordered by pr (priority).
 */
@Repository
public interface DialplanRepository extends JpaRepository<Dialplan, Integer> {

    List<Dialplan> findByTenantId(UUID tenantId);

    List<Dialplan> findByDpid(Integer dpid);

    List<Dialplan> findByTenantIdAndDpid(UUID tenantId, Integer dpid);

    List<Dialplan> findBySubscriptionId(UUID subscriptionId);

    @Modifying
    void deleteByTenantId(UUID tenantId);

    @Modifying
    void deleteBySubscriptionId(UUID subscriptionId);
}