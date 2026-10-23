package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.AccountType;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Audience;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Send;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Template;
import com.dalai.llama.tenant.leadmanagement.notify.MailRenderer;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.repository.TenantRepository;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Ops mail to creators (news, tips, reminders): ops keep templates here and send one to every
 * active creator, to chosen creators, or to one address as a test. {name} in the subject or body
 * becomes the creator's first name. Sent as Dalai Llama (the brands alias, {@link PlatformMailer}),
 * off the request thread once the send is logged. */
@Service
@RequiredArgsConstructor
public class CreatorMailService {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<]+[^\\s<.,;:!?)\\]'\"]");

    public record TemplateRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 10000) String body,
            Boolean active
    ) {
    }

    /** {@code tenantIds} empty or null = every active creator; {@code testEmail} set = only that address. */
    public record SendRequest(@NotNull UUID templateId, List<UUID> tenantIds, @Email String testEmail) {
    }

    public record SendResult(Audience audience, int recipients) {
    }

    public record Recipient(UUID tenantId, String name, String email) {
    }

    /** The shared layout's fields (classpath:mail/layout.html). */
    record MailLayout(String title, String preheader, String audience, String contentHtml, String contentText, String footer,
                      String unsubscribeHtml, String unsubscribeText, String openPixelHtml) {
    }

    private final CreatorMailStore store;
    private final TenantRepository tenants;
    private final PlatformMailer mailer;
    private final MailRenderer renderer;

    public List<Template> templates() {
        return store.templates();
    }

    public Template create(TemplateRequest request) {
        return store.insert(request.name().trim(), request.subject().trim(), request.body());
    }

    public Template update(UUID id, TemplateRequest request) {
        return store.update(id, request.name().trim(), request.subject().trim(), request.body(),
                request.active() == null || request.active()).orElseThrow(CreatorMailService::notFound);
    }

    public List<Send> recentSends() {
        return store.recentSends(20);
    }

    /** Active creators, newest first, for the ops picker. */
    public List<Recipient> creators() {
        return tenants.findActiveTenants().stream()
                .filter(t -> t.getAccountType() == AccountType.CREATOR && t.getDeletedAt() == null)
                .sorted(java.util.Comparator.comparing(Tenant::getCreatedAt, java.util.Comparator.nullsLast(java.util.Comparator.reverseOrder())))
                .map(t -> new Recipient(t.getId(), t.getPrimaryContactName(), t.getPrimaryContactEmail()))
                .toList();
    }

    @Transactional
    public SendResult send(SendRequest request) {
        Template template = store.find(request.templateId()).orElseThrow(CreatorMailService::notFound);
        if (!template.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Template is switched off");
        List<Recipient> recipients;
        Audience audience;
        if (request.testEmail() != null && !request.testEmail().isBlank()) {
            recipients = List.of(new Recipient(null, null, request.testEmail().trim()));
            audience = Audience.TEST;
        } else if (request.tenantIds() == null || request.tenantIds().isEmpty()) {
            recipients = creators();
            audience = Audience.ALL;
        } else {
            Set<UUID> chosen = Set.copyOf(request.tenantIds());
            recipients = creators().stream().filter(r -> chosen.contains(r.tenantId())).toList();
            audience = Audience.CHOSEN;
        }
        recipients.forEach(r -> mailer.sendAfterCommit(mail(template, r)));
        store.logSend(template.id(), template.subject(), audience, recipients.size());
        return new SendResult(audience, recipients.size());
    }

    PlatformMailer.Mail mail(Template template, Recipient to) {
        String name = firstName(to.name());
        String subject = fill(template.subject(), name);
        String body = fill(template.body(), name);
        MailLayout layout = new MailLayout(subject, preview(body), "FOR CREATORS", html(body), body,
                "You're getting this because you have a creator account on Dalai Llama. ", "", "", "");
        return new PlatformMailer.Mail(to.email(), subject, renderer.text("layout", layout), renderer.html("layout", layout));
    }

    /** "Hi {name}" with no name reads "Hi there". */
    private static String fill(String s, String firstName) {
        return s.replace("{name}", firstName == null ? "there" : firstName);
    }

    private static String firstName(String full) {
        if (full == null || full.isBlank()) return null;
        return full.trim().split("\\s+")[0];
    }

    /** Paragraphs on blank lines, line breaks kept, links clickable; everything else escaped. */
    static String html(String body) {
        StringBuilder out = new StringBuilder();
        for (String paragraph : body.trim().split("\\r?\\n\\s*\\r?\\n")) {
            String escaped = MailRenderer.escape(paragraph.trim());
            String linked = URL.matcher(escaped).replaceAll(m -> "<a href=\"" + m.group() + "\" style=\"color:#6d28d9\">" + m.group() + "</a>");
            out.append("<p style=\"margin:0 0 16px;font-size:16px;line-height:1.6;color:#3d3550\">")
                    .append(linked.replaceAll("\\r?\\n", "<br>")).append("</p>");
        }
        return out.toString();
    }

    private static String preview(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) : flat;
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
    }
}
