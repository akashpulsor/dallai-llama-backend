package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.common.token.PublicTokens;
import com.dalai.llama.tenant.leadmanagement.notify.MailLabels;
import com.dalai.llama.tenant.leadmanagement.notify.MailRenderer;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms.MailableFilm;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.inspiration.InspirationStore.MailIdea;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/** Turns videos into mailers: the creator's own mail (a letter from them, subject and intro from an
 * email template, rule 27) and the digest from Dalai Llama. Every video shows what a brand needs to
 * judge it (picture, who it was made for, format, length, shape), a link to watch it and a button to
 * request one like it. A request opens the brands site's request form, and the router gives it to
 * the creator who mailed (BrandInquiryService#submitToDalaillama). A creator with no film of their
 * own yet mails inspiration videos instead, credited to their makers. Links are tracked links minted
 * for this delivery; with no delivery yet (a preview) they go straight to the site. */
@Component
@RequiredArgsConstructor
public class OutreachComposer {

    public record Mail(String subject, String text, String html, Map<String, String> headers) {
    }

    /** What one mail (or digest block) shows: the creator's films, or with none, inspiration videos. */
    public record Content(List<MailableFilm> films, List<MailIdea> ideas) {
        public static Content of(List<MailableFilm> films) {
            return new Content(films, List.of());
        }

        public static Content ideas(List<MailIdea> ideas) {
            return new Content(List.of(), ideas);
        }

        public boolean isEmpty() {
            return films.isEmpty() && ideas.isEmpty();
        }

        public int size() {
            return films.size() + ideas.size();
        }

        String firstTitle() {
            return films.isEmpty() ? ideas.get(0).title() : films.get(0).title();
        }

        ShowcaseIndustry industry() {
            return films.isEmpty() ? ideas.get(0).industry() : films.get(0).industry();
        }
    }

    /** Why a creator's block is in the digest. */
    public enum DigestReason { WROTE_TO_YOU, YOU_FOLLOW, PICKED_FOR_YOU }

    /** One creator's block in the digest: their videos and what they wrote (null for picks and
     * follow notices). */
    public record DigestSection(UUID tenantId, String creatorName, String handle, DigestReason reason, String message, Content content) {
    }

    record SectionContext(String label, String heading, String messageHtml, String messageText, String filmsHtml, String filmsText,
                          String requestHtml, String requestText) {
    }

    record CreatorMailContext(String subject, String preheader, String greeting, String intro, String noteHtml, String note,
                              String filmsHtml, String filmsText, String ask, String requestHtml, String requestText,
                              String creatorName, String handle, String profileUrl, String unsubscribeUrl, String openPixelHtml) {
    }

    /** One video as a mailer shows it: picture, title, who it was made for, its format and length,
     * then "watch" and "request one like it". */
    record FilmContext(String title, String imageUrl, int imageWidth, String madeFor, String details, String watchLabel, String watchUrl,
                       String requestLabel, String requestUrl) {
    }

    record TextContext(String text) {
    }

    record ButtonContext(String label, String url) {
    }

    record MailLayout(String title, String preheader, String audience, String contentHtml, String contentText, String footer,
                      String unsubscribeHtml, String unsubscribeText, String openPixelHtml) {
    }

    private final MailRenderer renderer;
    private final OutreachStore store;
    private final ShowcaseProperties showcaseProperties;
    private final OutreachProperties properties;

    /** The creator's own mail: a letter from them with their videos and a request button.
     * {@code delivery} null = preview: nothing is stored, links are plain links. */
    public Mail creatorMail(EmailTemplateStore.EmailTemplate template, Content content, UUID tenantId, String creatorName, String handle,
                            String recipientName, String note, OutreachStore.Delivery delivery) {
        Content shown = limit(content, template.layout().maxFilms());
        // Reads as a personal email, not a newsletter (plain text style, links not buttons), so it
        // lands in the inbox rather than Promotions.
        Videos videos = videos(shown, tenantId, handle, delivery, "Request one like this", "letter_film");
        String unsubscribe = unsubscribeUrl(delivery);
        String subject = fill(template.subject(), shown, creatorName, recipientName);
        String intro = fill(template.intro(), shown, creatorName, recipientName);
        String personal = blank(note);
        ButtonContext request = new ButtonContext("Request a video from me", requestUrl(tenantId, handle, null, null, shown.industry(), delivery));
        String ask = shown.films().isEmpty()
                ? "These aren't my videos; they're ideas. Tell me what you need and I'll make one for your brand: send me a request "
                + "and you'll get a brief with the price straight away."
                : "If you have a video coming up, I'd be glad to make it for you. Send me a request and you'll get a brief with "
                + "the price straight away.";
        CreatorMailContext context = new CreatorMailContext(subject, preheader(shown), greeting(recipientName), intro,
                personal.isEmpty() ? "" : renderer.html("letter_note", new TextContext(personal)), personal,
                videos.html, videos.text, ask, renderer.html("link", request), renderer.text("link", request),
                creatorName, handle, profileUrl(handle, null, null), unsubscribe, openPixel(delivery));
        return new Mail(subject, renderer.text("creator_mail", context),
                renderer.html("creator_mail", context), unsubscribeHeaders(unsubscribe));
    }

    /** The held mail of several creators merged into one email from Dalai Llama: a block per
     * creator with what they wrote, their videos and a button to request a video from them. */
    public Mail digest(List<DigestSection> sections, String recipientName, OutreachStore.Delivery delivery) {
        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        TextContext intro = new TextContext(greeting(recipientName) + " " + (sections.size() == 1
                ? "a creator on Dalai Llama wanted you to see this."
                : sections.size() + " creators on Dalai Llama wanted you to see this today.")
                + " Like something? Request a video and you'll get a brief with the price straight away.");
        html.append(renderer.html("p", intro)).append("</td></tr>");
        text.append(renderer.text("p", intro));
        for (DigestSection section : sections) {
            SectionContext context = section(section, delivery);
            html.append(renderer.html("digest_section", context));
            text.append(renderer.text("digest_section", context));
        }
        ButtonContext discover = new ButtonContext("Discover more creators", showcaseProperties.publicBaseUrl() + "/creators");
        html.append("<tr><td style=\"padding:8px 32px 8px\">").append(renderer.html("button", discover));
        text.append(renderer.text("button", discover));
        String unsubscribe = unsubscribeUrl(delivery);
        String subject = digestSubject(sections);
        MailLayout layout = new MailLayout(subject, preheader(sections.get(0).content()),
                "FOR BRANDS", html.toString(), text.toString(),
                "You're getting this because creators on Dalai Llama wanted to show you their work. We group it into one email, at most one a day. ",
                "<a href=\"" + MailRenderer.escape(unsubscribe) + "\" style=\"color:#7a7088\">Unsubscribe</a>",
                "Unsubscribe: " + unsubscribe, openPixel(delivery));
        return new Mail(subject, renderer.text("layout", layout), renderer.html("layout", layout), unsubscribeHeaders(unsubscribe));
    }

    private SectionContext section(DigestSection section, OutreachStore.Delivery delivery) {
        Videos videos = videos(section.content(), section.tenantId(), section.handle(), delivery,
                "Ask " + section.creatorName() + " for one like this", "film");
        String message = blank(section.message());
        ButtonContext request = new ButtonContext("Request a video from " + section.creatorName(),
                requestUrl(section.tenantId(), section.handle(), null, null, section.content().industry(), delivery));
        return new SectionContext(label(section), heading(section),
                message.isEmpty() ? "" : renderer.html("note", new TextContext(message)), message,
                videos.html, videos.text, renderer.html("digest_request", request), renderer.text("button", request));
    }

    private static String label(DigestSection section) {
        if (section.content().films().isEmpty()) return "IDEAS FOR YOUR BRAND";
        return switch (section.reason()) {
            case WROTE_TO_YOU -> "A CREATOR WROTE TO YOU";
            case YOU_FOLLOW -> "NEW FROM A CREATOR YOU FOLLOW";
            case PICKED_FOR_YOU -> "PICKED FOR YOUR INDUSTRY";
        };
    }

    private static String heading(DigestSection section) {
        if (section.content().films().isEmpty()) return section.creatorName() + " found videos you may want for your brand";
        return switch (section.reason()) {
            case WROTE_TO_YOU -> section.creatorName();
            case YOU_FOLLOW -> section.creatorName() + " posted a new film";
            case PICKED_FOR_YOU -> section.creatorName() + "'s work for brands like yours";
        };
    }

    private static String digestSubject(List<DigestSection> sections) {
        if (sections.size() > 1) return sections.size() + " creators on Dalai Llama shared videos with you";
        DigestSection only = sections.get(0);
        if (only.content().films().isEmpty()) return "Video ideas for your brand · from " + only.creatorName();
        List<MailableFilm> films = only.content().films();
        return films.size() == 1 ? films.get(0).title() + " · from " + only.creatorName() : films.size() + " films from " + only.creatorName();
    }

    private record Videos(String html, String text) {
    }

    private Videos videos(Content content, UUID tenantId, String handle, OutreachStore.Delivery delivery, String requestLabel,
                          String template) {
        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        List<FilmContext> blocks = new ArrayList<>();
        for (MailableFilm f : content.films()) blocks.add(film(f, delivery, requestLabel));
        for (MailIdea i : content.ideas()) blocks.add(idea(i, tenantId, handle, delivery, requestLabel));
        for (FilmContext block : blocks) {
            html.append(renderer.html(template, block));
            text.append(renderer.text(template, block));
        }
        return new Videos(html.toString(), text.toString());
    }

    private FilmContext film(MailableFilm film, OutreachStore.Delivery delivery, String requestLabel) {
        // The creator's page with this film first (and their films for that industry next).
        String watch = link(delivery, film.tenantId(), film.itemId(),
                profileUrl(film.handle(), film.industry() == null ? null : film.industry().name(), film.publicId()));
        String request = requestUrl(film.tenantId(), film.handle(), film.publicId(), null, film.industry(), delivery);
        return new FilmContext(film.title(), film.thumbnailUrl() == null ? "" : film.thumbnailUrl(), film.vertical() ? 300 : 536,
                madeFor(film), details(film), "Watch the film", watch, requestLabel, request);
    }

    /** An inspiration video: never passed off as the creator's work; credited to its maker. */
    private FilmContext idea(MailIdea idea, UUID tenantId, String handle, OutreachStore.Delivery delivery, String requestLabel) {
        boolean vertical = idea.posterWidth() != null && idea.posterHeight() != null && idea.posterHeight() > idea.posterWidth();
        String industry = MailLabels.industry(idea.industry());
        String madeFor = "Found on Pinterest" + (idea.makerName() == null ? "" : " · by " + idea.makerName());
        String details = (industry == null ? "" : industry + " · ") + "An idea for your brand, not made on Dalai Llama";
        return new FilmContext(idea.title(), idea.posterUrl(), vertical ? 300 : 536, madeFor, details,
                "Watch on Pinterest", link(delivery, tenantId, null, idea.pinUrl()), requestLabel,
                requestUrl(tenantId, handle, null, idea.pinId(), idea.industry(), delivery));
    }

    /** The brands site's request form, opened for this creator; the router gives the request to them. */
    private String requestUrl(UUID tenantId, String handle, String publicId, String pinId, ShowcaseIndustry industry,
                              OutreachStore.Delivery delivery) {
        StringBuilder url = new StringBuilder(showcaseProperties.publicBaseUrl()).append("/creators?then=request&creator=")
                .append(encode(handle));
        if (publicId != null) url.append("&film=").append(encode(publicId));
        if (pinId != null) url.append("&inspiration=").append(encode(pinId));
        if (industry != null) url.append("&industry=").append(industry.name());
        return link(delivery, tenantId, null, url.toString());
    }

    /** A tracked link for this delivery: ?ref= carries its token on, so a request made from it is
     * attributed. Pinterest links go through the redirect too but get no ?ref=. */
    private String link(OutreachStore.Delivery delivery, UUID tenantId, UUID itemId, String target) {
        if (delivery == null) return target;
        String token = PublicTokens.newToken();
        boolean ours = target.startsWith(showcaseProperties.publicBaseUrl());
        store.addLink(token, delivery.id(), tenantId, itemId, ours ? target + (target.contains("?") ? "&" : "?") + "ref=" + token : target);
        return properties.publicApiBaseUrl() + "/r/" + token;
    }

    /** "Made for Hearth Foods · Food & beverage", or for an unnamed client "Made for a food & beverage brand". */
    static String madeFor(MailableFilm film) {
        String industry = MailLabels.industry(film.industry());
        if (film.clientLabel() != null && !film.clientLabel().isBlank()) {
            return "Made for " + film.clientLabel().trim() + (industry == null ? "" : " · " + industry);
        }
        return industry == null ? "Made for a brand" : "Made for a " + industry.toLowerCase(Locale.ROOT) + " brand";
    }

    /** "Social short · 1:00 · Vertical · Made on Dalai Llama for a paying client". */
    static String details(MailableFilm film) {
        List<String> parts = new ArrayList<>();
        if (MailLabels.format(film.format()) != null) parts.add(MailLabels.format(film.format()));
        if (MailLabels.duration(film.durationSeconds()) != null) parts.add(MailLabels.duration(film.durationSeconds()));
        if (film.format() != null) parts.add(film.vertical() ? "Vertical" : "Widescreen");
        // Every mailable film is a delivered, paid, client-approved project (rule 9).
        parts.add("Made on Dalai Llama for a paying client");
        return String.join(" · ", parts);
    }

    /** The inbox preview line: what's inside. */
    private static String preheader(Content content) {
        if (content.isEmpty()) return "";
        String more = content.size() > 1 ? " and " + (content.size() - 1) + " more" : "";
        String what = content.films().isEmpty() ? "video ideas for your brand" : madeFor(content.films().get(0));
        return content.firstTitle() + more + " · " + what;
    }

    private static Content limit(Content content, int max) {
        return new Content(content.films().subList(0, Math.min(content.films().size(), max)),
                content.ideas().subList(0, Math.min(content.ideas().size(), max)));
    }

    private String profileUrl(String handle, String industry, String publicId) {
        StringBuilder url = new StringBuilder(showcaseProperties.publicBaseUrl()).append("/c/").append(handle);
        String sep = "?";
        if (publicId != null) {
            url.append(sep).append("film=").append(encode(publicId));
            sep = "&";
        }
        if (industry != null) url.append(sep).append("industry=").append(encode(industry));
        return url.toString();
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    /** Tells us when the mail is opened (the send-time signal); none in previews. */
    private String openPixel(OutreachStore.Delivery delivery) {
        return delivery == null ? "" : "<img src=\"" + properties.publicApiBaseUrl() + "/o/" + delivery.id()
                + ".gif\" width=\"1\" height=\"1\" alt=\"\" style=\"display:block;border:0;width:1px;height:1px\">";
    }

    private String unsubscribeUrl(OutreachStore.Delivery delivery) {
        return delivery == null ? properties.publicApiBaseUrl() + "/unsubscribe/preview"
                : properties.publicApiBaseUrl() + "/unsubscribe/" + delivery.unsubscribeToken();
    }

    /** RFC 8058 one-click: mail clients POST to the URL to unsubscribe. */
    private static Map<String, String> unsubscribeHeaders(String unsubscribeUrl) {
        return Map.of("List-Unsubscribe", "<" + unsubscribeUrl + ">", "List-Unsubscribe-Post", "List-Unsubscribe=One-Click");
    }

    /** Template placeholders: {film} (first video's title), {creator}, {industry} ("food & beverage";
     * "your industry" when the video has none), {name}. */
    static String fill(String copy, Content content, String creatorName, String recipientName) {
        String industry = MailLabels.industry(content.industry());
        return copy.replace("{film}", content.firstTitle())
                .replace("{creator}", creatorName)
                .replace("{industry}", industry == null ? "your industry" : industry.toLowerCase(Locale.ROOT))
                .replace("{name}", recipientName == null || recipientName.isBlank() ? "there" : recipientName.trim());
    }

    private static String greeting(String name) {
        return name == null || name.isBlank() ? "Hi," : "Hi " + name.trim() + ",";
    }

    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }
}
