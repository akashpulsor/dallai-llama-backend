package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.AccountType;
import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValues;
import com.dalai.llama.tenant.leadmanagement.audience.LeadCsvParser;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Audience;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.QueuedMail;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Recipient;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Send;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Template;
import com.dalai.llama.tenant.leadmanagement.notify.MailRenderer;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.outreach.MailIdeas;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.inspiration.InspirationStore.MailIdea;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachStore;
import com.dalai.llama.tenant.leadmanagement.outreach.RecipientHasher;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.showcase.service.CreatorProfileLinks;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Ops mail to creators (news, nudges, invites). Ops keep templates and send one to every active
 * creator, chosen creators, creators whose profile has no video yet, or the creator leads ops
 * uploaded (people not on the platform yet); or to one address as a test. {name} becomes the first
 * name. Mail is queued and leaves a limited number a day ({@code creator-mail.daily-cap}; the mailbox
 * is shared with outreach and sign-in mail), each with an unsubscribe link into the shared
 * suppression list. A template reaches an address once. Sent as Dalai Llama (the brands alias). */
@Slf4j
@Service
public class CreatorMailService {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<]+[^\\s<.,;:!?)\\]'\"]");

    /** {@code ctaLabel} + {@code ctaUrl}: an optional button the mail ends with (both, or neither). */
    public record TemplateRequest(
            @NotBlank @Size(max = 80) String name,
            @NotBlank @Size(max = 200) String subject,
            @NotBlank @Size(max = 10000) String body,
            @Size(max = 40) String ctaLabel,
            @Size(max = 300) @jakarta.validation.constraints.Pattern(regexp = "^$|https?://\\S+", message = "The button link must start with https://") String ctaUrl,
            @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(3) Integer ideaVideos,
            Boolean active
    ) {
        public TemplateRequest(String name, String subject, String body, Boolean active) {
            this(name, subject, body, null, null, null, active);
        }

        public TemplateRequest(String name, String subject, String body, String ctaLabel, String ctaUrl, Boolean active) {
            this(name, subject, body, ctaLabel, ctaUrl, null, active);
        }
    }

    /** A draft as the recipient will see it. */
    public record PreviewRequest(@Size(max = 200) String subject, @Size(max = 10000) String body, @Size(max = 40) String ctaLabel,
                                 @Size(max = 300) String ctaUrl,
                                 @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(3) Integer ideaVideos) {
        public PreviewRequest(String subject, String body, String ctaLabel, String ctaUrl) {
            this(subject, body, ctaLabel, ctaUrl, null);
        }
    }

    public record Preview(String subject, String html) {
    }

    record ButtonContext(String label, String url) {
    }

    /** One inspiration video as a card (classpath:mail/idea_card.html). */
    record IdeaCard(String title, String imageUrl, int imageWidth, String framePadding, String imageRadius, String details, String credit,
                    String watchUrl) {
    }

    /** {@code audience} null: {@code testEmail} set = TEST, {@code tenantIds} given = CHOSEN, else ALL. */
    public record SendRequest(@NotNull UUID templateId, Audience audience, List<UUID> tenantIds, @Email String testEmail) {
        public SendRequest(UUID templateId, List<UUID> tenantIds, String testEmail) {
            this(templateId, null, tenantIds, testEmail);
        }
    }

    /** {@code recipients}: queued now; {@code alreadyHad}: this template reached them before;
     * {@code unsubscribed}: on the suppression list. */
    public record SendResult(Audience audience, int recipients, int alreadyHad, int unsubscribed) {
        public SendResult(Audience audience, int recipients) {
            this(audience, recipients, 0, 0);
        }
    }

    /** A creator on the platform as the ops picker shows them. */
    public record CreatorView(UUID tenantId, String name, String email, String publicProfileUrl, Integer liveVideos) {
    }

    public record LeadImportResult(int rows, int added, int alreadyKnown, int rejected, int totalLeads) {
    }

    public record Overview(int creatorLeads, int queued, int dailyCap, int sentToday) {
    }

    /** The shared layout's fields (classpath:mail/layout.html). */
    record MailLayout(String title, String preheader, String audience, String contentHtml, String contentText, String footer,
                      String unsubscribeHtml, String unsubscribeText, String openPixelHtml) {
    }

    private final CreatorMailStore store;
    private final TenantRepository tenants;
    private final CreatorProfileLinks profiles;
    private final PlatformMailer mailer;
    private final MailRenderer renderer;
    private final RecipientHasher hasher;
    private final OutreachStore suppression;
    private final OutreachProperties outreach;
    private final MailIdeas mailIdeas;
    private final ShowcaseProperties showcase;
    private final Clock clock;
    private final int dailyCap;
    private final int perRun;

    public CreatorMailService(CreatorMailStore store, TenantRepository tenants, CreatorProfileLinks profiles, PlatformMailer mailer,
                              MailRenderer renderer, RecipientHasher hasher, OutreachStore suppression, OutreachProperties outreach,
                              MailIdeas mailIdeas, ShowcaseProperties showcase, Clock clock,
                              @Value("${creator-mail.daily-cap:150}") int dailyCap,
                              @Value("${creator-mail.per-run:20}") int perRun) {
        this.mailIdeas = mailIdeas;
        this.showcase = showcase;
        this.store = store;
        this.tenants = tenants;
        this.profiles = profiles;
        this.mailer = mailer;
        this.renderer = renderer;
        this.hasher = hasher;
        this.suppression = suppression;
        this.outreach = outreach;
        this.clock = clock;
        this.dailyCap = dailyCap;
        this.perRun = perRun;
    }

    public List<Template> templates() {
        return store.templates();
    }

    public Template create(TemplateRequest request) {
        return store.insert(request.name().trim(), request.subject().trim(), request.body(), ctaLabel(request), ctaUrl(request),
                ideaVideos(request.ideaVideos()));
    }

    public Template update(UUID id, TemplateRequest request) {
        return store.update(id, request.name().trim(), request.subject().trim(), request.body(), ctaLabel(request), ctaUrl(request),
                ideaVideos(request.ideaVideos()),
                request.active() == null || request.active()).orElseThrow(CreatorMailService::notFound);
    }

    /** The draft rendered exactly as it will be mailed (first name "Asha" for {name}). */
    public Preview preview(PreviewRequest r) {
        Template draft = new Template(null, "preview", blankTo(r.subject(), "(no subject)"), blankTo(r.body(), ""),
                blank(r.ctaLabel()) ? null : r.ctaLabel().trim(), blank(r.ctaUrl()) ? null : r.ctaUrl().trim(), ideaVideos(r.ideaVideos()),
                true, null);
        PlatformMailer.Mail mail = mail(draft, "preview@example.com", "Asha", null, ideasFor(draft));
        return new Preview(mail.subject(), mail.html());
    }

    /** Both button fields, or neither. */
    private static String ctaLabel(TemplateRequest r) {
        if (blank(r.ctaLabel()) != blank(r.ctaUrl())) throw new IllegalArgumentException("Give the button both a label and a link, or neither");
        return blank(r.ctaLabel()) ? null : r.ctaLabel().trim();
    }

    private static String ctaUrl(TemplateRequest r) {
        return blank(r.ctaUrl()) ? null : r.ctaUrl().trim();
    }

    private static boolean blank(String s) {
        return s == null || s.isBlank();
    }

    private static String blankTo(String s, String fallback) {
        return blank(s) ? fallback : s;
    }

    public List<Send> recentSends() {
        return store.recentSends(20);
    }

    public Overview overview() {
        return new Overview(store.leadCount(), store.queuedCount(), dailyCap, store.sentOn(today()));
    }

    /** Active creators, newest first, with their public profile and how many videos it shows. */
    public List<CreatorView> creators() {
        Map<UUID, CreatorProfileLinks.Link> links = profiles.all();
        return tenants.findActiveTenants().stream()
                .filter(t -> t.getAccountType() == AccountType.CREATOR && t.getDeletedAt() == null)
                .sorted(Comparator.comparing(Tenant::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
                .map(t -> {
                    CreatorProfileLinks.Link link = links.get(t.getId());
                    return new CreatorView(t.getId(), t.getPrimaryContactName(), t.getPrimaryContactEmail(),
                            link == null ? null : link.url(), link == null ? null : link.liveVideos());
                })
                .toList();
    }

    @Transactional
    public SendResult send(SendRequest request) {
        Template template = store.find(request.templateId()).orElseThrow(CreatorMailService::notFound);
        if (!template.active()) throw new ResponseStatusException(HttpStatus.CONFLICT, "Template is switched off");
        Audience audience = audience(request);
        if (audience == Audience.TEST) {
            Recipient tester = recipient(request.testEmail(), null);
            UUID sendId = store.logSend(template.id(), template.subject(), Audience.TEST, 1);
            String token = store.recordTest(sendId, template.id(), tester);
            mailer.sendAfterCommit(mail(template, tester.email(), null, token, ideasFor(template)));
            return new SendResult(Audience.TEST, 1);
        }
        if (audience == Audience.LEADS && !hasher.configured()) {
            // Their unsubscribe must work before anyone outside the platform is mailed.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mail to creator leads needs outreach switched on (OUTREACH_HASH_PEPPER)");
        }
        List<Recipient> recipients = recipients(audience, request.tenantIds());
        UUID sendId = store.logSend(template.id(), template.subject(), audience, 0);
        int queued = 0, alreadyHad = 0, unsubscribed = 0;
        for (Recipient r : recipients) {
            if (r.recipientHash() != null && suppression.isSuppressed(r.recipientHash())) unsubscribed++;
            else if (store.enqueue(sendId, template.id(), r)) queued++;
            else alreadyHad++;
        }
        store.setRecipients(sendId, queued);
        return new SendResult(audience, queued, alreadyHad, unsubscribed);
    }

    /** Sends the next queued mails, within the day's cap. */
    @Scheduled(fixedDelayString = "${creator-mail.dispatch-delay-ms:60000}", initialDelayString = "${creator-mail.dispatch-delay-ms:60000}")
    public void scheduled() {
        int sent = dispatch();
        if (sent > 0) log.info("Creator mail: {} sent", sent);
    }

    public int dispatch() {
        int room = Math.min(perRun, dailyCap - store.sentOn(today()));
        if (room <= 0) return 0;
        Map<UUID, Template> templates = new java.util.HashMap<>();
        // The same inspiration videos for every mail of a template in this run.
        Map<UUID, List<MailIdea>> ideas = new java.util.HashMap<>();
        int sent = 0;
        for (QueuedMail queued : store.nextQueued(room)) {
            if (queued.recipientHash() != null && suppression.isSuppressed(queued.recipientHash())) {
                store.mark(queued.id(), "SKIPPED");
                continue;
            }
            Template template = templates.computeIfAbsent(queued.templateId(), id -> store.find(id).orElseThrow());
            boolean ok = mailer.send(mail(template, queued.email(), queued.name(), queued.unsubscribeToken(),
                    ideas.computeIfAbsent(template.id(), id -> ideasFor(template))));
            store.mark(queued.id(), ok ? "SENT" : "FAILED");
            if (ok) sent++;
        }
        return sent;
    }

    /** Creator leads from a CSV (the audience columns: email, name, website for their channel or page).
     * Only the email is needed; an address already known keeps its details. */
    @Transactional
    public LeadImportResult importLeads(byte[] file) {
        if (file.length > 2 * 1024 * 1024) throw new IllegalArgumentException("Upload a file of at most 2 MB");
        int added = 0, known = 0, rejected = 0;
        List<LeadCsvParser.ParsedRow> rows = LeadCsvParser.parse(new String(file, StandardCharsets.UTF_8));
        for (LeadCsvParser.ParsedRow row : rows) {
            String email = row.emails().stream().map(ContactPointValues::normaliseEmail).filter(ContactPointValues::validEmail)
                    .findFirst().orElse(null);
            if (email == null) {
                rejected++;
                continue;
            }
            if (store.upsertLead(new CreatorMailStore.Lead(email, row.name(), row.website()))) added++;
            else known++;
        }
        return new LeadImportResult(rows.size(), added, known, rejected, store.leadCount());
    }

    private Audience audience(SendRequest r) {
        if (r.audience() != null) {
            if (r.audience() == Audience.TEST && (r.testEmail() == null || r.testEmail().isBlank())) {
                throw new IllegalArgumentException("Give the address for the test");
            }
            return r.audience();
        }
        if (r.testEmail() != null && !r.testEmail().isBlank()) return Audience.TEST;
        return r.tenantIds() == null || r.tenantIds().isEmpty() ? Audience.ALL : Audience.CHOSEN;
    }

    private List<Recipient> recipients(Audience audience, List<UUID> tenantIds) {
        return switch (audience) {
            case LEADS -> store.leadsNotOnPlatform().stream().map(l -> recipient(l.email(), l.name())).toList();
            case NO_VIDEO -> creators().stream().filter(c -> c.liveVideos() == null || c.liveVideos() == 0)
                    .map(c -> recipient(c.email(), c.name())).toList();
            case CHOSEN -> {
                Set<UUID> chosen = Set.copyOf(tenantIds == null ? List.of() : tenantIds);
                yield creators().stream().filter(c -> chosen.contains(c.tenantId())).map(c -> recipient(c.email(), c.name())).toList();
            }
            default -> creators().stream().map(c -> recipient(c.email(), c.name())).toList();
        };
    }

    private Recipient recipient(String email, String name) {
        String normalised = ContactPointValues.normaliseEmail(email);
        return new Recipient(normalised, name, hasher.configured() ? hasher.hash(normalised) : null);
    }

    /** {@code unsubscribeToken} null for a test: the link then goes nowhere. */
    /** Videos from the inspiration board (only ones with a picture), as many as the template asks for. */
    private List<MailIdea> ideasFor(Template template) {
        return template.ideaVideos() > 0 ? mailIdeas.forIndustries(List.of(), template.ideaVideos()) : List.of();
    }

    private static int ideaVideos(Integer n) {
        return n == null ? 0 : Math.max(0, Math.min(3, n));
    }

    /** An inspiration video, credited to its maker; it plays on the public inspiration board. */
    private IdeaCard card(MailIdea idea) {
        boolean vertical = idea.posterWidth() != null && idea.posterHeight() != null && idea.posterHeight() > idea.posterWidth();
        String industry = com.dalai.llama.tenant.leadmanagement.notify.MailLabels.industry(idea.industry());
        String watch = showcase.publicBaseUrl() + "/inspiration" + (idea.industry() == null ? "" : "?industry=" + idea.industry().name());
        return new IdeaCard(idea.title(), idea.posterUrl(), vertical ? 240 : 542, vertical ? "18px 0" : "0", vertical ? "10px" : "14px 14px 0 0",
                (industry == null ? "" : industry + " · ") + "Video idea", idea.makerName() == null ? "From the inspiration board" : "By " + idea.makerName(),
                watch);
    }

    PlatformMailer.Mail mail(Template template, String to, String fullName, String unsubscribeToken, List<MailIdea> ideas) {
        String name = firstName(fullName);
        String subject = fill(template.subject(), name);
        String body = fill(template.body(), name);
        String unsubscribe = outreach.publicApiBaseUrl() + "/unsubscribe/" + (unsubscribeToken == null ? "preview" : unsubscribeToken);
        String contentHtml = html(body);
        String contentText = body;
        if (!ideas.isEmpty()) {
            // Cards are rows of the layout's table: close the text cell, add them, reopen a cell.
            StringBuilder cards = new StringBuilder("</td></tr>");
            StringBuilder cardsText = new StringBuilder();
            for (MailIdea idea : ideas) {
                IdeaCard card = card(idea);
                cards.append(renderer.html("idea_card", card));
                cardsText.append(renderer.text("idea_card", card));
            }
            contentHtml += cards.append("<tr><td style=\"padding:0 28px 10px\">");
            contentText += "\n\n" + cardsText;
        }
        if (template.ctaLabel() != null && template.ctaUrl() != null) {
            ButtonContext button = new ButtonContext(fill(template.ctaLabel(), name), template.ctaUrl());
            contentHtml += "<div style=\"margin:8px 0 6px\">" + renderer.html("button", button) + "</div>";
            contentText += "\n\n" + renderer.text("button", button);
        }
        MailLayout layout = new MailLayout(subject, preheader(body), "FOR CREATORS", contentHtml, contentText,
                "You're getting this from Dalai Llama, the platform where brands find creators for their videos. ",
                "<a href=\"" + MailRenderer.escape(unsubscribe) + "\" style=\"color:#7a7088\">Unsubscribe</a>",
                "Unsubscribe: " + unsubscribe, "");
        return new PlatformMailer.Mail(to, subject, renderer.text("layout", layout), renderer.html("layout", layout),
                Map.of("List-Unsubscribe", "<" + unsubscribe + ">", "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"));
    }

    /** "Hi {name}" with no name reads "Hi there". */
    static String fill(String s, String firstName) {
        if (firstName != null) return s.replace("{name}", firstName);
        // No name: "{name}, brands are..." becomes "Brands are...", and "Hi {name}" reads "Hi there".
        String trimmed = s.stripLeading();
        if (trimmed.startsWith("{name}")) {
            String rest = trimmed.substring("{name}".length()).replaceFirst("^[,:\\s-]+", "");
            s = rest.isEmpty() ? rest : Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
        }
        return s.replace("{name}", "there");
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

    private static String preheader(String body) {
        String flat = body.replaceAll("\\s+", " ").trim();
        return flat.length() > 120 ? flat.substring(0, 120) : flat;
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
    }
}
