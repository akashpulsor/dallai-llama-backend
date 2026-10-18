package com.dalai.llama.tenant.leadmanagement.routing;

import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.brand.BrandContactRepository;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiry;
import com.dalai.llama.tenant.leadmanagement.inquiry.BrandInquiryRepository;
import com.dalai.llama.tenant.leadmanagement.routing.RequestRoutingStore.OfferStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Finds the creator for a request sent to Dalaillama, by the strategy ops set
 * ({@link RoutingStrategy}). A request nobody can take -- no active creator left, or ops assigns --
 * waits unassigned in the ops queue; the brand sees "finding a creator" meanwhile.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestRouter {

    private final RequestRoutingStore store;
    private final BrandInquiryRepository inquiryRepository;
    private final BrandContactRepository contactRepository;
    private final CreatorRequestNotifier notifier;
    private final Clock clock;

    /** Hands out a request that has no creator. */
    @Transactional
    public void route(UUID inquiryId) {
        BrandInquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow();
        if (inquiry.getTenantId() != null) return;
        RoutingStrategy strategy = store.strategy();
        List<UUID> candidates = strategy == RoutingStrategy.OPS_ASSIGNS ? List.of() : store.candidates(inquiryId, inquiry.getIndustry());
        if (candidates.isEmpty()) {
            log.info("Request {} waits for ops ({}): no creator to route it to", inquiryId, strategy);
            return;
        }
        if (strategy == RoutingStrategy.ROUND_ROBIN_BY_INDUSTRY) {
            assign(inquiry, candidates.get(0));
            return;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        BrandContact brand = brand(inquiry);
        for (UUID creator : candidates) {
            store.offer(inquiryId, creator, OfferStatus.OFFERED, now);
            notifier.offered(creator, brand, inquiry);
        }
    }

    /** A creator takes a request offered to them; false when another creator was first. */
    @Transactional
    public boolean accept(UUID tenantId, UUID inquiryId) {
        if (!store.isOffered(inquiryId, tenantId)) throw new IllegalArgumentException("This request isn't offered to you");
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!store.claim(inquiryId, tenantId)) {
            store.answer(inquiryId, tenantId, OfferStatus.WITHDRAWN, now);
            return false;
        }
        store.answer(inquiryId, tenantId, OfferStatus.ASSIGNED, now);
        store.withdrawOthers(inquiryId, tenantId, now);
        store.touchRouted(tenantId, now);
        return true;
    }

    /**
     * A creator passes on a routed request -- one assigned to them or only offered. It is never
     * offered to them again; one that was theirs goes back to routing (round-robin moves on to the
     * next creator; offer-to-all and ops leave it with the others or in the ops queue).
     */
    @Transactional
    public void decline(UUID tenantId, UUID inquiryId) {
        BrandInquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow(() -> new IllegalArgumentException("No such request"));
        boolean holder = tenantId.equals(inquiry.getTenantId());
        if (!holder && !store.isOffered(inquiryId, tenantId)) throw new IllegalArgumentException("No such request");
        store.offer(inquiryId, tenantId, OfferStatus.DECLINED, OffsetDateTime.now(clock));
        if (!holder) return;
        // Through the entity, not SQL: route() reads this same (cached) entity next.
        inquiry.setTenantId(null);
        inquiry.setStatus(BrandInquiry.Status.NEW);
        inquiryRepository.saveAndFlush(inquiry);
        if (store.strategy() == RoutingStrategy.ROUND_ROBIN_BY_INDUSTRY) route(inquiryId);
    }

    /** Ops gives a waiting request to a creator, whatever the strategy. */
    @Transactional
    public void assignByOps(UUID inquiryId, UUID tenantId) {
        BrandInquiry inquiry = inquiryRepository.findById(inquiryId)
                .filter(i -> i.getSource() == BrandInquiry.Source.LANDING)
                .orElseThrow(() -> new IllegalArgumentException("No such routed request"));
        if (tenantId.equals(inquiry.getTenantId())) return;
        if (inquiry.getTenantId() != null) throw new IllegalStateException("This request already has a creator");
        assign(inquiry, tenantId);
    }

    private void assign(BrandInquiry inquiry, UUID tenantId) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!store.claim(inquiry.getId(), tenantId)) {
            // Asked twice (a double click while the first was still making the brief): already done.
            if (tenantId.equals(store.holder(inquiry.getId()))) return;
            throw new IllegalStateException("This request already has a creator");
        }
        store.offer(inquiry.getId(), tenantId, OfferStatus.ASSIGNED, now);
        store.withdrawOthers(inquiry.getId(), tenantId, now);
        store.touchRouted(tenantId, now);
    }

    private BrandContact brand(BrandInquiry inquiry) {
        return contactRepository.findById(inquiry.getBrandContactId()).orElseThrow();
    }
}
