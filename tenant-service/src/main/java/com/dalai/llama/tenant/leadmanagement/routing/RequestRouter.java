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
 * ({@link RoutingStrategy}).
 * <p>
 * With Dalaillama's own creator account set ({@link RequestMatchingProperties#houseCreatorEmail()},
 * the founder's), a request is never without a creator: one nobody asked a creator for is held by
 * that account at once, so the brand gets its brief and pays straight away. Once paid it is
 * offered to creators for the matching window, like a ride waiting for a driver: offer-to-all
 * offers it to every creator, round-robin to one at a time, ops-assigns leaves it to ops. The
 * first creator to accept takes it; when nobody has by the end of the window (or everyone
 * passed), the founder keeps it and makes it.
 * <p>
 * Without that account, a request nobody can take waits unassigned in the ops queue before the
 * brand can pay.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RequestRouter {

    private final RequestRoutingStore store;
    private final BrandInquiryRepository inquiryRepository;
    private final BrandContactRepository contactRepository;
    private final CreatorRequestNotifier notifier;
    private final RequestMatchingProperties matching;
    private final Clock clock;

    /** Dalaillama's own creator account, when one is set and has a creator profile. */
    public java.util.Optional<UUID> house() {
        return store.houseCreator(matching.houseCreatorEmail());
    }

    /** Hands out a request that has no creator. */
    @Transactional
    public void route(UUID inquiryId) {
        route(inquiryId, null);
    }

    /**
     * The same, for a request the brand sent from a creator's mail: it goes to that creator
     * ({@code preferred}) whatever the strategy, as a request from their page would, while they
     * are active, accepting requests and haven't passed on it. Otherwise it is routed as usual.
     */
    @Transactional
    public void route(UUID inquiryId, UUID preferred) {
        BrandInquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow();
        if (inquiry.getTenantId() != null) return;
        if (preferred != null && store.candidates(inquiryId, inquiry.getIndustry()).contains(preferred)) {
            assign(inquiry, preferred);
            return;
        }
        java.util.Optional<UUID> house = house();
        if (house.isPresent()) {
            // The brand pays first; creators are offered it once it is paid (startMatching).
            store.holdForHouse(inquiryId, house.get());
            return;
        }
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

    /**
     * The brand paid a request Dalaillama holds: it is offered to creators until the window ends.
     * False when there is nobody to offer it to (Dalaillama's account keeps it at once).
     */
    @Transactional
    public boolean startMatching(BrandInquiry inquiry) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        RoutingStrategy strategy = store.strategy();
        List<UUID> candidates = strategy == RoutingStrategy.OPS_ASSIGNS ? List.of() : creatorsFor(inquiry);
        if (candidates.isEmpty() && strategy != RoutingStrategy.OPS_ASSIGNS) return false;
        store.startMatching(inquiry.getId(), now.plus(matching.window()));
        BrandContact brand = brand(inquiry);
        for (UUID creator : strategy == RoutingStrategy.ROUND_ROBIN_BY_INDUSTRY ? candidates.subList(0, 1) : candidates) {
            store.offer(inquiry.getId(), creator, OfferStatus.OFFERED, now);
            notifier.offeredPaid(creator, brand, inquiry, matching.window());
        }
        return true;
    }

    /** Active creators for a paid request, Dalaillama's own account aside (it keeps what nobody takes). */
    private List<UUID> creatorsFor(BrandInquiry inquiry) {
        java.util.Optional<UUID> house = house();
        return store.candidates(inquiry.getId(), inquiry.getIndustry()).stream()
                .filter(c -> house.isEmpty() || !house.get().equals(c)).toList();
    }

    /** A creator takes a request offered to them; false when another creator was first. */
    @Transactional
    public boolean accept(UUID tenantId, UUID inquiryId) {
        if (!store.isOffered(inquiryId, tenantId)) throw new IllegalArgumentException("This request isn't offered to you");
        OffsetDateTime now = OffsetDateTime.now(clock);
        boolean held = inquiryRepository.findById(inquiryId).map(BrandInquiry::isProvisional).orElse(false);
        if (!(held ? store.takeFromHouse(inquiryId, tenantId) : store.claim(inquiryId, tenantId))) {
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
     * next creator; offer-to-all and ops leave it with the others or in the ops queue). A paid
     * request being matched goes on to the next creator under round-robin. True when nobody it was
     * offered to is left, so Dalaillama's account keeps it now.
     */
    @Transactional
    public boolean decline(UUID tenantId, UUID inquiryId) {
        BrandInquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow(() -> new IllegalArgumentException("No such request"));
        boolean holder = tenantId.equals(inquiry.getTenantId());
        if (holder && inquiry.isProvisional()) {
            throw new IllegalStateException("Dalaillama holds this request until a creator takes it: assign it or let matching finish");
        }
        if (!holder && !store.isOffered(inquiryId, tenantId)) throw new IllegalArgumentException("No such request");
        OffsetDateTime now = OffsetDateTime.now(clock);
        store.offer(inquiryId, tenantId, OfferStatus.DECLINED, now);
        if (!holder) {
            if (!inquiry.isProvisional() || inquiry.getMatchingUntil() == null) return false;
            RoutingStrategy strategy = store.strategy();
            if (strategy == RoutingStrategy.OPS_ASSIGNS) return false;
            if (strategy == RoutingStrategy.ROUND_ROBIN_BY_INDUSTRY) {
                creatorsFor(inquiry).stream().filter(c -> !store.everOffered(inquiryId, c)).findFirst().ifPresent(next -> {
                    store.offer(inquiryId, next, OfferStatus.OFFERED, now);
                    notifier.offeredPaid(next, brand(inquiry), inquiry, matching.window());
                });
            }
            return store.openOffers(inquiryId) == 0;
        }
        // Through the entity, not SQL: route() reads this same (cached) entity next.
        inquiry.setTenantId(null);
        inquiry.setStatus(BrandInquiry.Status.NEW);
        inquiryRepository.saveAndFlush(inquiry);
        // Never left without a creator: with Dalaillama's account set, it holds it again.
        if (store.strategy() == RoutingStrategy.ROUND_ROBIN_BY_INDUSTRY || house().isPresent()) route(inquiryId);
        return false;
    }

    /** Ops gives a waiting request to a creator, whatever the strategy. */
    @Transactional
    public void assignByOps(UUID inquiryId, UUID tenantId) {
        BrandInquiry inquiry = inquiryRepository.findById(inquiryId)
                .filter(i -> i.getSource() == BrandInquiry.Source.LANDING)
                .orElseThrow(() -> new IllegalArgumentException("No such routed request"));
        if (inquiry.isProvisional()) {
            // Dalaillama holds it: ops hand it to a creator (or to Dalaillama's account for good).
            if (!store.takeFromHouse(inquiryId, tenantId)) throw new IllegalStateException("This request already has a creator");
            OffsetDateTime now = OffsetDateTime.now(clock);
            store.offer(inquiryId, tenantId, OfferStatus.ASSIGNED, now);
            store.withdrawOthers(inquiryId, tenantId, now);
            store.touchRouted(tenantId, now);
            return;
        }
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
