package com.dalai.llama.tenant.leadmanagement.creatormail;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.domain.entity.enums.AccountType;
import com.dalai.llama.tenant.leadmanagement.audience.ContactPointValues;
import com.dalai.llama.tenant.leadmanagement.audience.LeadCsvParser;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Audience;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.BrandFilter;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.MailFor;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.MailStyle;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.QueuedMail;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Recipient;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Send;
import com.dalai.llama.tenant.leadmanagement.creatormail.CreatorMailStore.Template;
import com.dalai.llama.tenant.leadmanagement.notify.MailRenderer;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.leadmanagement.outreach.MailIdeas;
import com.dalai.llama.tenant.leadmanagement.outreach.OutreachProperties;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
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
 * suppression list. A template reaches an address once. Sent as a plain letter from a
 * person ({@code creator-mail.from-name}) on the outreach domain. */
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
            /* null: a random industry each mail (a brand's own industry, for brand mail) */ ShowcaseIndustry ideaIndustry,
            MailStyle style,
            /* who it's written for, set when created; null: creators */ MailFor mailFor,
            Boolean active
    ) {
        public TemplateRequest(String name, String subject, String body, Boolean active) {
            this(name, subject, body, null, null, null, null, null, null, active);
        }

        public TemplateRequest(String name, String subject, String body, String ctaLabel, String ctaUrl, Boolean active) {
            this(name, subject, body, ctaLabel, ctaUrl, null, null, null, null, active);
        }

        public TemplateRequest(String name, String subject, String body, String ctaLabel, String ctaUrl, Integer ideaVideos, Boolean active) {
            this(name, subject, body, ctaLabel, ctaUrl, ideaVideos, null, null, null, active);
        }

        public TemplateRequest(String name, String subject, String body, String ctaLabel, String ctaUrl, Integer ideaVideos, MailStyle style,
                               Boolean active) {
            this(name, subject, body, ctaLabel, ctaUrl, ideaVideos, null, style, null, active);
        }

        public TemplateRequest(String name, String subject, String body, String ctaLabel, String ctaUrl, Integer ideaVideos,
                               ShowcaseIndustry ideaIndustry, MailStyle style, Boolean active) {
            this(name, subject, body, ctaLabel, ctaUrl, ideaVideos, ideaIndustry, style, null, active);
        }
    }

    /** A draft as the recipient will see it. */
    public record PreviewRequest(@Size(max = 200) String subject, @Size(max = 10000) String body, @Size(max = 40) String ctaLabel,
                                 @Size(max = 300) String ctaUrl,
                                 @jakarta.validation.constraints.Min(0) @jakarta.validation.constraints.Max(3) Integer ideaVideos,
                                 ShowcaseIndustry ideaIndustry, MailStyle style, MailFor mailFor) {
        public PreviewRequest(String subject, String body, String ctaLabel, String ctaUrl) {
            this(subject, body, ctaLabel, ctaUrl, null, null, null, null);
        }

        public PreviewRequest(String subject, String body, String ctaLabel, String ctaUrl, Integer ideaVideos) {
            this(subject, body, ctaLabel, ctaUrl, ideaVideos, null, null, null);
        }

        public PreviewRequest(String subject, String body, String ctaLabel, String ctaUrl, Integer ideaVideos, ShowcaseIndustry ideaIndustry,
                              MailStyle style) {
            this(subject, body, ctaLabel, ctaUrl, ideaVideos, ideaIndustry, style, null);
        }
    }

    record ButtonContext(String label, String url) {
    }

    /** One inspiration video as a card (classpath:mail/idea_card.html), for the styled mail. */
    record IdeaCard(String title, String imageUrl, int imageWidth, String framePadding, String imageRadius, String details, String credit,
                    String watchUrl) {
    }

    /** The shared layout's fields (classpath:mail/layout.html), for the styled mail. */
    record MailLayout(String title, String preheader, String audience, String contentHtml, String contentText, String footer,
                      String unsubscribeHtml, String unsubscribeText, String openPixelHtml) {
    }

    public record Preview(String subject, String html) {
    }

    /** {@code audience} null: {@code testEmail} set = TEST, {@code tenantIds} given = CHOSEN, else ALL. */
    public record SendRequest(@NotNull UUID templateId, Audience audience, List<UUID> tenantIds, @Email String testEmail,
                              /* for BRANDS */ BrandFilter brands) {
        public SendRequest(UUID templateId, List<UUID> tenantIds, String testEmail) {
            this(templateId, null, tenantIds, testEmail, null);
        }

        public SendRequest(UUID templateId, Audience audience, List<UUID> tenantIds, String testEmail) {
            this(templateId, audience, tenantIds, testEmail, null);
        }
    }

    /** How many brands a filter reaches (one contact each), and a few of their names. */
    public record BrandAudience(int brands, List<String> sample) {
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
    private final PlatformMailer.Sender sender;

    public CreatorMailService(CreatorMailStore store, TenantRepository tenants, CreatorProfileLinks profiles, PlatformMailer mailer,
                              MailRenderer renderer, RecipientHasher hasher, OutreachStore suppression, OutreachProperties outreach,
                              MailIdeas mailIdeas, ShowcaseProperties showcase, Clock clock,
                              @Value("${creator-mail.daily-cap:150}") int dailyCap,
                              @Value("${creator-mail.per-run:20}") int perRun,
                              @Value("${creator-mail.from-name:Akash from Dalai Llama}") String fromName,
                              @Value("${creator-mail.from-address:hello@partner.dalaillama.in}") String fromAddress) {
        this.sender = new PlatformMailer.Sender(fromName, fromAddress);
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

    public List<Template> templates(MailFor mailFor) {
        return store.templates(mailFor == null ? MailFor.CREATORS : mailFor);
    }

    /** The brands a brand mail with this filter would go to. */
    public BrandAudience brandAudience(BrandFilter filter) {
        List<Recipient> brands = store.brandContacts(filter);
        return new BrandAudience(brands.size(), brands.stream().map(Recipient::company).limit(5).toList());
    }

    public Template create(TemplateRequest request) {
        return store.insert(request.name().trim(), request.subject().trim(), request.body(), ctaLabel(request), ctaUrl(request),
                ideaVideos(request.ideaVideos()), request.ideaIndustry(), style(request.style()),
                request.mailFor() == null ? MailFor.CREATORS : request.mailFor());
    }

    public Template update(UUID id, TemplateRequest request) {
        return store.update(id, request.name().trim(), request.subject().trim(), request.body(), ctaLabel(request), ctaUrl(request),
                ideaVideos(request.ideaVideos()), request.ideaIndustry(), style(request.style()),
                request.active() == null || request.active()).orElseThrow(CreatorMailService::notFound);
    }

    /** The draft rendered exactly as it will be mailed (first name "Asha" for {name}; a brand mail is
     * shown as to Hearth Foods, a food brand). */
    public Preview preview(PreviewRequest r) {
        MailFor mailFor = r.mailFor() == null ? MailFor.CREATORS : r.mailFor();
        Template draft = new Template(null, "preview", blankTo(r.subject(), "(no subject)"), blankTo(r.body(), ""),
                blank(r.ctaLabel()) ? null : r.ctaLabel().trim(), blank(r.ctaUrl()) ? null : r.ctaUrl().trim(), ideaVideos(r.ideaVideos()),
                r.ideaIndustry(), style(r.style()), mailFor, true, null);
        Recipient to = mailFor == MailFor.BRANDS
                ? new Recipient("preview@example.com", "Asha", null, "Hearth Foods", ShowcaseIndustry.FOOD_BEVERAGE)
                : new Recipient("preview@example.com", "Asha", null);
        PlatformMailer.Mail mail = mail(draft, to, null, ideasFor(draft, to.industry()));
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
            Recipient tester = tester(template, ContactPointValues.normaliseEmail(request.testEmail()));
            UUID sendId = store.logSend(template.id(), template.subject(), Audience.TEST, 1);
            String token = store.recordTest(sendId, template.id(), tester);
            mailer.sendAfterCommit(mail(template, tester, token, ideasFor(template, tester.industry())));
            return new SendResult(Audience.TEST, 1);
        }
        if ((audience == Audience.BRANDS) != (template.mailFor() == MailFor.BRANDS)) {
            throw new IllegalArgumentException(template.mailFor() == MailFor.BRANDS
                    ? "This template is written for brands; send it to brands" : "This template is written for creators; send it to creators");
        }
        if ((audience == Audience.LEADS || audience == Audience.BRANDS) && !hasher.configured()) {
            // Their unsubscribe must work before anyone outside the platform is mailed.
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Mail to people outside the platform needs outreach switched on (OUTREACH_HASH_PEPPER)");
        }
        List<Recipient> recipients = audience == Audience.BRANDS
                ? store.brandContacts(request.brands() == null ? new BrandFilter(List.of(), List.of(), List.of()) : request.brands()).stream()
                        .map(b -> recipient(b.email(), b.name(), b.company(), b.industry())).toList()
                : recipients(audience, request.tenantIds());
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
        int sent = 0;
        for (QueuedMail queued : store.nextQueued(room)) {
            if (queued.recipientHash() != null && suppression.isSuppressed(queued.recipientHash())) {
                store.mark(queued.id(), "SKIPPED", clock.instant());
                continue;
            }
            Template template = templates.computeIfAbsent(queued.templateId(), id -> store.find(id).orElseThrow());
            Recipient to = new Recipient(queued.email(), queued.name(), queued.recipientHash(), queued.company(), queued.industry());
            boolean ok = mailer.send(mail(template, to, queued.unsubscribeToken(), ideasFor(template, to.industry())));
            store.mark(queued.id(), ok ? "SENT" : "FAILED", clock.instant());
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
        return recipient(email, name, null, null);
    }

    private Recipient recipient(String email, String name, String company, ShowcaseIndustry industry) {
        String normalised = ContactPointValues.normaliseEmail(email);
        return new Recipient(normalised, name, hasher.configured() ? hasher.hash(normalised) : null, company, industry);
    }

    /** Who a test goes to, as the real mail would see them: a brand contact of the directory for a
     * brand mail, else the first known name that reads as a name (an account named after its address doesn't). */
    private Recipient tester(Template template, String email) {
        if (template.mailFor() == MailFor.BRANDS) {
            var brand = store.brandContact(email);
            if (brand.isPresent()) return recipient(email, brand.get().name(), brand.get().company(), brand.get().industry());
        }
        String name = store.namesOf(email).stream().filter(n -> firstName(n) != null).findFirst().orElse(null);
        return recipient(email, name);
    }

    /** Videos from the inspiration board (only ones with a picture), as many as the template asks for, drawn
     * fresh for every mail: from the template's industry, or from a random industry when it names none. */
    private List<MailIdea> ideasFor(Template template, ShowcaseIndustry recipientIndustry) {
        if (template.ideaVideos() == 0) return List.of();
        ShowcaseIndustry industry = template.ideaIndustry() != null ? template.ideaIndustry()
                : template.mailFor() == MailFor.BRANDS ? recipientIndustry : null;
        return mailIdeas.pick(industry, template.ideaVideos());
    }

    private List<MailIdea> ideasFor(Template template) {
        return ideasFor(template, null);
    }

    private static MailStyle style(MailStyle style) {
        return style == null ? MailStyle.PLAIN : style;
    }

    private static int ideaVideos(Integer n) {
        return n == null ? 0 : Math.max(0, Math.min(3, n));
    }

    /** Where an inspiration video plays: the public inspiration board, on its industry. */
    private String watchUrl(MailIdea idea) {
        return showcase.publicBaseUrl() + "/inspiration" + (idea.industry() == null ? "" : "?industry=" + idea.industry().name());
    }

    /** A plain personal letter, the way one person writes to another: no layout, pictures, buttons,
     * badges or bulk-mail headers, few links, from a person. Gmail files designed newsletter mail
     * under Promotions whoever sends it; this keeps creator mail in the main inbox. The ideas are a
     * short list of titles with one link to watch them; the button is a plain link.
     * {@code unsubscribeToken} null only for the preview. */
    PlatformMailer.Mail mail(Template template, String to, String fullName, String unsubscribeToken, List<MailIdea> ideas) {
        return mail(template, new Recipient(to, fullName, null), unsubscribeToken, ideas);
    }

    PlatformMailer.Mail mail(Template template, Recipient recipient, String unsubscribeToken, List<MailIdea> ideas) {
        String to = recipient.email();
        String first = firstName(recipient.name());
        if (first == null) first = nameFromEmail(to);
        Fill fill = new Fill(first, recipient.company(), template.mailFor());
        String unsubscribe = outreach.publicApiBaseUrl() + "/unsubscribe/" + (unsubscribeToken == null ? "preview" : unsubscribeToken);
        return template.style() == MailStyle.STYLED
                ? styled(template, to, fill, unsubscribe, ideas)
                : plain(template, to, fill, unsubscribe, ideas);
    }

    /** What a mail's placeholders become for one recipient: {name} their first name, {company} their brand. */
    record Fill(String firstName, String company, MailFor mailFor) {
        String apply(String s) {
            String filled = fill(s, firstName, mailFor == MailFor.BRANDS ? "there" : "creator");
            return filled.replace("{company}", company == null || company.isBlank() ? "your brand" : company.trim());
        }
    }

    private PlatformMailer.Mail plain(Template template, String to, Fill fill, String unsubscribe, List<MailIdea> ideas) {
        String subject = fill.apply(template.subject());
        String body = fill.apply(template.body());
        StringBuilder html = new StringBuilder(html(body));
        StringBuilder text = new StringBuilder(body.trim());
        if (!ideas.isEmpty()) {
            html.append("<ul style=\"margin:0 0 14px;padding-left:20px\">");
            text.append("\n");
            for (MailIdea idea : ideas) {
                String credit = idea.makerName() == null ? "" : " (by " + idea.makerName() + ")";
                html.append("<li style=\"margin:0 0 6px\">").append(MailRenderer.escape(idea.title() + credit)).append("</li>");
                text.append("\n- ").append(idea.title()).append(credit);
            }
            String watch = watchUrl(ideas.get(0));
            html.append("</ul>").append(paragraph("You can watch them here: " + link(watch, MailRenderer.escape(watch))));
            text.append("\n\nYou can watch them here: ").append(watch);
        }
        if (template.ctaLabel() != null && template.ctaUrl() != null) {
            String label = fill.apply(template.ctaLabel());
            html.append(paragraph(link(template.ctaUrl(), MailRenderer.escape(label))));
            text.append("\n\n").append(label).append(": ").append(template.ctaUrl());
        }
        // "Akash from Dalai Llama" signs as "Akash" over "Dalai Llama".
        String signature = sender.name().replaceFirst("\\s+from\\s+", "\n");
        html.append(paragraph(MailRenderer.escape(signature).replace("\n", "<br>")));
        text.append("\n\n").append(signature);
        html.append("<p style=\"margin:22px 0 0;font-size:12px;color:#888\">Not interested? ")
                .append(link(unsubscribe, "Unsubscribe")).append(" and I won't write again.</p>");
        text.append("\n\n--\nNot interested? Unsubscribe and I won't write again: ").append(unsubscribe);
        String page = "<div style=\"font-family:Arial,Helvetica,sans-serif;font-size:14px;line-height:1.55;color:#222\">" + html + "</div>";
        return new PlatformMailer.Mail(to, subject, text.toString(), page, Map.of(), sender);
    }

    /** The designed platform mail: the shared layout, ideas as picture cards, the link as a button. */
    private PlatformMailer.Mail styled(Template template, String to, Fill fill, String unsubscribe, List<MailIdea> ideas) {
        String subject = fill.apply(template.subject());
        String body = fill.apply(template.body());
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
            ButtonContext button = new ButtonContext(fill.apply(template.ctaLabel()), template.ctaUrl());
            contentHtml += "<div style=\"margin:8px 0 6px\">" + renderer.html("button", button) + "</div>";
            contentText += "\n\n" + renderer.text("button", button);
        }
        String flat = body.replaceAll("\\s+", " ").trim();
        MailLayout layout = new MailLayout(subject, flat.length() > 120 ? flat.substring(0, 120) : flat,
                template.mailFor() == MailFor.BRANDS ? "FOR BRANDS" : "FOR CREATORS", contentHtml,
                contentText, "You're getting this from Dalai Llama, the platform where brands find creators for their videos. ",
                link(unsubscribe, "Unsubscribe"), "Unsubscribe: " + unsubscribe, "");
        return new PlatformMailer.Mail(to, subject, renderer.text("layout", layout), renderer.html("layout", layout),
                Map.of("List-Unsubscribe", "<" + unsubscribe + ">", "List-Unsubscribe-Post", "List-Unsubscribe=One-Click"), sender);
    }

    /** An inspiration video as a card, credited to its maker; it plays on the public inspiration board. */
    private IdeaCard card(MailIdea idea) {
        boolean vertical = idea.posterWidth() != null && idea.posterHeight() != null && idea.posterHeight() > idea.posterWidth();
        String industry = com.dalai.llama.tenant.leadmanagement.notify.MailLabels.industry(idea.industry());
        return new IdeaCard(idea.title(), idea.posterUrl(), vertical ? 240 : 542, vertical ? "18px 0" : "0", vertical ? "10px" : "14px 14px 0 0",
                (industry == null ? "" : industry + " · ") + "Video idea", idea.makerName() == null ? "From the inspiration board" : "By " + idea.makerName(),
                watchUrl(idea));
    }

    private static String paragraph(String innerHtml) {
        return "<p style=\"margin:0 0 14px\">" + innerHtml + "</p>";
    }

    private static String link(String url, String labelHtml) {
        return "<a href=\"" + MailRenderer.escape(url) + "\">" + labelHtml + "</a>";
    }

    /** "{name}" becomes the first name. With none: a leading "{name}," drops out ("{name}, brands are..."
     * reads "Brands are..."), anywhere else it reads "creator" ("Hi creator,"). */
    static String fill(String s, String firstName) {
        return fill(s, firstName, "creator");
    }

    /** {@code fallback}: what {name} reads when there's no name and it isn't leading ("creator", "there"). */
    static String fill(String s, String firstName, String fallback) {
        if (firstName != null) return s.replace("{name}", firstName);
        String trimmed = s.stripLeading();
        if (trimmed.startsWith("{name}")) {
            String rest = trimmed.substring("{name}".length()).replaceFirst("^[,:\\s-]+", "");
            s = rest.isEmpty() ? rest : Character.toUpperCase(rest.charAt(0)) + rest.substring(1);
        }
        return s.replace("{name}", fallback);
    }

    /** Words an address starts with that are not a person's name (info@, hello.studio@, team_x@). */
    private static final Set<String> NOT_NAMES = Set.of("info", "contact", "hello", "hey", "team", "admin", "support", "sales", "mail",
            "email", "studio", "studios", "media", "films", "film", "official", "business", "collab", "collabs", "work", "enquiry",
            "enquiries", "inquiry", "booking", "bookings", "marketing", "creator", "creators", "office", "manager", "management", "care",
            "help", "noreply", "partner", "partners", "partnerships", "brand", "brands", "ads", "connect", "social", "youtube", "insta",
            "instagram", "the", "real", "its", "itsme", "music", "photo", "photos", "photography", "video", "videos", "vlogs",
            "press", "ops", "jobs", "career", "careers", "accounts", "billing", "mrs");

    /** The first name an address plainly carries: asha.rao@ and asha_92@ give "Asha"; info@, a short
     * word, or one long run of letters (akashtripathi@, two names run together) give none. */
    static String nameFromEmail(String email) {
        if (email == null || !email.contains("@")) return null;
        String local = email.substring(0, email.indexOf('@')).replaceFirst("\\+.*", "").toLowerCase(java.util.Locale.ROOT);
        String[] words = local.split("[._\\-0-9]+");
        String first = words.length > 0 && !words[0].isEmpty() ? words[0] : words.length > 1 ? words[1] : "";
        if (!first.matches("[a-z]{3,10}") || NOT_NAMES.contains(first)) return null;
        if (words.length == 1 && first.length() > 8) return null;
        return Character.toUpperCase(first.charAt(0)) + first.substring(1);
    }

    /** The first name a contact name gives. Many sign-ups carry their address or a handle as the name
     * ("akash.t@gmail.com", "asha_rao92"): those are read like an address, so "asha_rao92" still gives
     * "Asha" and "akashtripathi.2801@gmail.com" gives none; a word that isn't plainly a name gives none. */
    static String firstName(String full) {
        if (full == null || full.isBlank()) return null;
        String first = full.trim().split("\\s+")[0];
        if (!first.matches("[\\p{L}'-]+")) return nameFromEmail(first.contains("@") ? first : first + "@x");
        if (first.length() < 2 || first.length() > 20) return null;
        return Character.toUpperCase(first.charAt(0)) + first.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    /** Paragraphs on blank lines, line breaks kept, links clickable; everything else escaped. */
    static String html(String body) {
        StringBuilder out = new StringBuilder();
        for (String paragraph : body.trim().split("\\r?\\n\\s*\\r?\\n")) {
            String escaped = MailRenderer.escape(paragraph.trim());
            String linked = URL.matcher(escaped).replaceAll(m -> "<a href=\"" + m.group() + "\">" + m.group() + "</a>");
            out.append(paragraph(linked.replaceAll("\\r?\\n", "<br>")));
        }
        return out.toString();
    }

    private LocalDate today() {
        return LocalDate.now(clock.withZone(ZoneOffset.UTC));
    }

    private static ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Template not found");
    }
}
