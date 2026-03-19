package com.dalai.llama.pbx.core.repository.kamailio;

import com.dalai.llama.pbx.core.domain.entity.kamailio.Subscriber;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Subscriber table — SIP digest authentication.
 *
 * Read paths:
 *   - FreeSwitchXmlController.directory() → findByUsernameAndDomain (mod_xml_curl per-call)
 *   - KamailioWriteController → bulk listing for tenant provisioning status
 *
 * Write paths:
 *   - KamailioWriteController → tenant-service inserts during provisioning
 *   - AgentService → auto-creates subscriber when agent is created
 *   - TrunkService → creates TRUNK-type subscriber for outbound
 *
 * Kamailio reads this table directly via auth_db module (not through PBX-Core).
 */
@Repository
public interface SubscriberRepository extends JpaRepository<Subscriber, Integer> {

    /**
     * Primary lookup for FreeSWITCH directory — mod_xml_curl sends user + domain.
     * Also used by AgentService to check if subscriber already exists before sync.
     */
    Optional<Subscriber> findByUsernameAndDomain(String username, String domain);

    /**
     * All subscribers for a tenant — used by provisioning status check
     * and bulk cleanup on deprovision.
     */
    List<Subscriber> findByTenantId(UUID tenantId);

    /**
     * Active subscribers only — used by FreeSWITCH directory to reject
     * disabled agents.
     */
    List<Subscriber> findByTenantIdAndIsActiveTrue(UUID tenantId);

    /**
     * Filter by type — e.g., list all AGENT subscribers for a tenant.
     */
    List<Subscriber> findByTenantIdAndSubscriberType(UUID tenantId, SubscriberType subscriberType);

    /**
     * By subscription — tenant-service provisions per subscription, so
     * deprovision also happens per subscription.
     */
    List<Subscriber> findBySubscriptionId(UUID subscriptionId);

    /**
     * Existence check before insert — AgentService checks this to avoid
     * duplicate subscriber rows when re-creating an agent.
     */
    boolean existsByUsernameAndDomain(String username, String domain);

    /**
     * Count per tenant — used by provisioning status and entitlement checks
     * (e.g., max_agents limit).
     */
    long countByTenantId(UUID tenantId);

    long countByTenantIdAndSubscriberType(UUID tenantId, SubscriberType subscriberType);

    /**
     * Bulk delete for tenant deprovision.
     * Called by KamailioWriteController DELETE /api/v1/write/kamailio/tenant/{tenantId}.
     */
    @Modifying
    void deleteByTenantId(UUID tenantId);

    @Modifying
    void deleteBySubscriptionId(UUID subscriptionId);

    /**
     * Suspend/resume all subscribers for a tenant.
     * Kamailio auth_db checks is_active — setting false rejects new REGISTER/INVITE.
     */
    @Modifying
    @Query("UPDATE Subscriber s SET s.isActive = :active, s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.tenantId = :tenantId")
    int updateActiveByTenantId(UUID tenantId, boolean active);
}
