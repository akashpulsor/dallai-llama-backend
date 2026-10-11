package com.dalai.llama.tenant.leadmanagement.audience;

import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.AudienceView;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.ContactPointView;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.LeadPage;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceDtos.LeadView;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore.LeadRow;
import com.dalai.llama.tenant.leadmanagement.audience.AudienceStore.PointRow;
import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValues.Kind;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** A creator's audiences and the leads in them, shown by rule 25. */
@Service
@RequiredArgsConstructor
public class AudienceService {

    static final int PAGE_SIZE = 50;
    private static final Set<String> VALIDATED = Set.of("VERIFIED", "LIKELY_VALID");

    private final AudienceStore store;

    public List<AudienceView> audiences(UUID tenantId) {
        return store.audiences(tenantId).stream()
                .map(a -> new AudienceView(a.id(), a.name(), a.createdAt(), a.leads(), a.reachable())).toList();
    }

    @Transactional
    public AudienceView create(UUID tenantId, String name) {
        String trimmed = name.trim();
        if (store.nameTaken(tenantId, trimmed)) throw new IllegalStateException("You already have an audience called " + trimmed);
        UUID id = store.createAudience(tenantId, trimmed);
        return audiences(tenantId).stream().filter(a -> a.id().equals(id)).findFirst().orElseThrow();
    }

    @Transactional
    public void delete(UUID tenantId, UUID audienceId) {
        requireOwned(tenantId, audienceId);
        store.deleteAudience(tenantId, audienceId);
    }

    public LeadPage leads(UUID tenantId, UUID audienceId, String q, int page) {
        requireOwned(tenantId, audienceId);
        int safePage = Math.max(0, page);
        List<LeadRow> rows = store.leads(tenantId, audienceId, q, safePage, PAGE_SIZE);
        Map<UUID, List<PointRow>> points = store.points(tenantId, rows.stream().map(LeadRow::id).toList()).stream()
                .collect(Collectors.groupingBy(PointRow::leadId));
        return new LeadPage(rows.stream().map(r -> view(r, points.getOrDefault(r.id(), List.of()))).toList(),
                safePage, PAGE_SIZE, store.countLeads(tenantId, audienceId, q));
    }

    @Transactional
    public void removeFromAudience(UUID tenantId, UUID audienceId, UUID leadId) {
        if (store.removeMember(tenantId, audienceId, leadId) == 0) throw new IllegalArgumentException("Not in this audience");
    }

    /** The creator says this contact point is wrong for this lead: unlink it from their lead. The
     * platform row stays (PLATFORM.md rule 7, never delete). */
    @Transactional
    public void discardContactPoint(UUID tenantId, UUID leadId, UUID contactPointId) {
        if (store.unlink(tenantId, leadId, contactPointId) == 0) throw new IllegalArgumentException("No such contact point on this lead");
    }

    static LeadView view(LeadRow lead, List<PointRow> points) {
        List<ContactPointView> emails = visible(points, Kind.EMAIL);
        List<ContactPointView> phones = visible(points, Kind.PHONE);
        int discarded = (int) points.stream().filter(p -> "INVALID".equals(p.status())).count();
        boolean reachable = emails.stream().anyMatch(e -> !e.unsubscribed());
        return new LeadView(lead.id(), lead.name(), lead.designation(), lead.company(), lead.industry(), lead.website(), emails, phones, discarded, reachable);
    }

    /** Validated ones if there are any, otherwise the unvalidated ones; never INVALID. */
    private static List<ContactPointView> visible(List<PointRow> points, Kind kind) {
        List<PointRow> ofKind = points.stream().filter(p -> p.kind() == kind && !"INVALID".equals(p.status())).toList();
        boolean anyValidated = ofKind.stream().anyMatch(p -> VALIDATED.contains(p.status()));
        return ofKind.stream()
                .filter(p -> !anyValidated || VALIDATED.contains(p.status()))
                .map(p -> new ContactPointView(p.contactPointId(), p.value(), p.status(), p.unsubscribed()))
                .toList();
    }

    private void requireOwned(UUID tenantId, UUID audienceId) {
        if (!store.owns(tenantId, audienceId)) throw new IllegalArgumentException("Unknown audience");
    }
}
