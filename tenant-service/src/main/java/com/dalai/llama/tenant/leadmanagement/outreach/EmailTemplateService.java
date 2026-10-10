package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.leadmanagement.outreach.EmailTemplateStore.EmailTemplate;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** Rule 27: global templates (ops) every creator sees, plus each creator's own. A template is a
 * layout (which films, how many) plus its own subject and intro with {film}, {creator},
 * {industry}, {name} placeholders. Deleting only deactivates, so past sends keep their template. */
@Service
@RequiredArgsConstructor
public class EmailTemplateService {

    public record TemplateRequest(
            @NotBlank @Size(max = 80) String name,
            @NotNull OutreachTemplate layout,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 1000) String intro,
            Boolean active
    ) {
    }

    public record TemplateView(UUID id, String name, OutreachTemplate layout, String subject, String intro, boolean global,
                               boolean active) {
        static TemplateView of(EmailTemplate t) {
            return new TemplateView(t.id(), t.name(), t.layout(), t.subject(), t.intro(), t.global(), t.active());
        }
    }

    private final EmailTemplateStore store;

    public List<TemplateView> usableBy(UUID tenantId) {
        return store.usableBy(tenantId).stream().map(TemplateView::of).toList();
    }

    /** The template a send uses: the named one (a global or the creator's own, active), else the
     * built-in global for {@code layout}. One of the two must be given. */
    public EmailTemplate resolve(UUID tenantId, UUID templateId, OutreachTemplate layout) {
        if (templateId != null) {
            return store.find(templateId)
                    .filter(t -> t.active() && (t.global() || t.tenantId().equals(tenantId)))
                    .orElseThrow(() -> new IllegalArgumentException("Unknown template"));
        }
        if (layout == null) throw new IllegalArgumentException("Choose a template");
        if (!layout.creatorSendable()) throw new IllegalArgumentException("That template isn't sent by creators");
        return store.defaultFor(layout).orElseThrow(() -> new IllegalStateException("No template for " + layout));
    }

    @Transactional
    public TemplateView create(UUID tenantId, TemplateRequest r) {
        requireSendable(r.layout());
        return TemplateView.of(store.insert(tenantId, r.name().trim(), r.layout(), r.subject().trim(), r.intro().trim()));
    }

    @Transactional
    public TemplateView update(UUID tenantId, UUID id, TemplateRequest r) {
        requireSendable(r.layout());
        EmailTemplate t = own(tenantId, id);
        return TemplateView.of(store.update(t.id(), r.name().trim(), r.layout(), r.subject().trim(), r.intro().trim(),
                r.active() == null || r.active()));
    }

    @Transactional
    public void deactivate(UUID tenantId, UUID id) {
        EmailTemplate t = own(tenantId, id);
        store.update(t.id(), t.name(), t.layout(), t.subject(), t.intro(), false);
    }

    // ---- ops: global templates ----

    public List<TemplateView> globals() {
        return store.globals().stream().map(TemplateView::of).toList();
    }

    @Transactional
    public TemplateView createGlobal(TemplateRequest r) {
        requireSendable(r.layout());
        return TemplateView.of(store.insert(null, r.name().trim(), r.layout(), r.subject().trim(), r.intro().trim()));
    }

    @Transactional
    public TemplateView updateGlobal(UUID id, TemplateRequest r) {
        requireSendable(r.layout());
        EmailTemplate t = store.find(id).filter(EmailTemplate::global).orElseThrow(() -> new IllegalArgumentException("Unknown template"));
        return TemplateView.of(store.update(t.id(), r.name().trim(), r.layout(), r.subject().trim(), r.intro().trim(),
                r.active() == null || r.active()));
    }

    private EmailTemplate own(UUID tenantId, UUID id) {
        return store.find(id).filter(t -> tenantId.equals(t.tenantId()))
                .orElseThrow(() -> new IllegalArgumentException("Unknown template"));
    }

    private static void requireSendable(OutreachTemplate layout) {
        if (!layout.creatorSendable()) throw new IllegalArgumentException("That layout isn't sent by creators");
    }
}
