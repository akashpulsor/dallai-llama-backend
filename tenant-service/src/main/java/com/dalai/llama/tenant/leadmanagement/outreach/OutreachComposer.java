package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.common.token.PublicTokens;
import com.dalai.llama.tenant.leadmanagement.outreach.MailableFilms.MailableFilm;
import com.dalai.llama.tenant.showcase.config.ShowcaseProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Turns films into mail: the creator's own mail (layout, subject and intro from an email
 * template, rule 27) and the daily digest. Every film link is a tracked link minted for this delivery; with no delivery yet (a
 * preview) links go straight to the profile. */
@Component
@RequiredArgsConstructor
public class OutreachComposer {

    public record Mail(String subject, String text, String html, Map<String, String> headers) {
    }

    /** Why a creator's block is in the digest. */
    public enum DigestReason { WROTE_TO_YOU, YOU_FOLLOW, PICKED_FOR_YOU }

    /** One creator's block in the digest: their films, what they wrote (null for picks and follow
     * notices) and the address replies go to (null if they have none). */
    public record DigestSection(String creatorName, DigestReason reason, String message, List<MailableFilm> films, String replyTo) {
    }

    record SectionContext(String heading, String messageHtml, String messageText, String cardsHtml, String cardsText,
                          String replyHtml, String replyText) {
    }

    record ReplyContext(String creatorName, String mailto, String replyTo) {
    }

    record CreatorMailContext(String headline, String greeting, String intro, String note, String filmsHtml, String filmsText,
                              String creatorName, String profileUrl, String unsubscribeUrl, String openPixelHtml) {
    }

    record DigestContext(String greeting, String intro, String cardsHtml, String cardsText, String discoverUrl,
                         String unsubscribeUrl, String openPixelHtml) {
    }

    record CardContext(String title, String subtitle, String thumbnailUrl, String url) {
    }

    private final OutreachRenderer renderer;
    private final OutreachStore store;
    private final ShowcaseProperties showcaseProperties;
    private final OutreachProperties properties;

    /** {@code delivery} null = preview: nothing is stored, links are plain profile links. */
    public Mail creatorMail(EmailTemplateStore.EmailTemplate template, List<MailableFilm> films, String creatorName, String handle,
                            String recipientName, String note, OutreachStore.Delivery delivery) {
        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (MailableFilm film : films.subList(0, Math.min(films.size(), template.layout().maxFilms()))) {
            CardContext card = card(film, film.clientLabel() == null ? industry(film) : film.clientLabel(), delivery);
            html.append(renderer.html("card", card));
            text.append(renderer.text("card", card));
        }
        String profileUrl = profileUrl(handle, null);
        String unsubscribe = unsubscribeUrl(delivery);
        String subject = fill(template.subject(), films, creatorName, recipientName);
        CreatorMailContext context = new CreatorMailContext(subject, greeting(recipientName),
                fill(template.intro(), films, creatorName, recipientName), blank(note),
                html.toString(), text.toString(), creatorName, profileUrl, unsubscribe, openPixel(delivery));
        return new Mail(subject, renderer.text("creator_mail", context),
                renderer.html("creator_mail", context), unsubscribeHeaders(unsubscribe));
    }

    /** The held mail of several creators merged into one email: a block per creator with what they
     * wrote, all their films and a button that replies to their own address. */
    public Mail digest(List<DigestSection> sections, String recipientName, OutreachStore.Delivery delivery) {
        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (DigestSection section : sections) {
            SectionContext context = section(section, delivery);
            html.append(renderer.html("digest_section", context));
            text.append(renderer.text("digest_section", context));
        }
        String unsubscribe = unsubscribeUrl(delivery);
        DigestContext context = new DigestContext(greeting(recipientName),
                sections.size() == 1 ? "A creator on Dalai Llama wanted you to see their work:"
                        : "Creators on Dalai Llama wanted you to see their work today:",
                html.toString(), text.toString(), showcaseProperties.publicBaseUrl() + "/creators", unsubscribe, openPixel(delivery));
        return new Mail(digestSubject(sections), renderer.text("digest", context), renderer.html("digest", context),
                unsubscribeHeaders(unsubscribe));
    }

    private SectionContext section(DigestSection section, OutreachStore.Delivery delivery) {
        StringBuilder cardsHtml = new StringBuilder();
        StringBuilder cardsText = new StringBuilder();
        for (MailableFilm film : section.films()) {
            CardContext card = card(film, film.clientLabel() == null ? industry(film) : film.clientLabel(), delivery);
            cardsHtml.append(renderer.html("card", card));
            cardsText.append(renderer.text("card", card));
        }
        String message = blank(section.message());
        String replyHtml = "";
        String replyText = "";
        if (section.replyTo() != null) {
            String subject = "About " + section.films().get(0).title();
            ReplyContext reply = new ReplyContext(section.creatorName(),
                    "mailto:" + section.replyTo() + "?subject=" + URLEncoder.encode(subject, StandardCharsets.UTF_8).replace("+", "%20"),
                    section.replyTo());
            replyHtml = renderer.html("digest_reply", reply);
            replyText = renderer.text("digest_reply", reply);
        }
        return new SectionContext(heading(section),
                message.isEmpty() ? "" : "<p style=\"margin:8px 0 0;font-size:14px;line-height:1.7;color:#3b3346\">"
                        + OutreachRenderer.escape(message).replace("\n", "<br>") + "</p>", message,
                cardsHtml.toString(), cardsText.toString(), replyHtml, replyText);
    }

    private static String heading(DigestSection section) {
        return switch (section.reason()) {
            case WROTE_TO_YOU -> section.creatorName() + " wrote to you";
            case YOU_FOLLOW -> "New from " + section.creatorName() + ", who you follow";
            case PICKED_FOR_YOU -> "Picked for you: " + section.creatorName() + "'s work for brands like yours";
        };
    }

    private static String digestSubject(List<DigestSection> sections) {
        if (sections.size() > 1) return sections.size() + " creators on Dalai Llama shared their work with you";
        DigestSection only = sections.get(0);
        return only.films().size() == 1 ? only.films().get(0).title() + " · from " + only.creatorName()
                : only.films().size() + " films from " + only.creatorName();
    }

    private CardContext card(MailableFilm film, String subtitle, OutreachStore.Delivery delivery) {
        String target = profileUrl(film.handle(), film.industry() == null ? null : film.industry().name());
        String url = target;
        if (delivery != null) {
            // ?ref= carries the link token to the profile, so a request made from it is attributed.
            String token = PublicTokens.newToken();
            store.addLink(token, delivery.id(), film.tenantId(), film.itemId(), target + "&ref=" + token);
            url = properties.publicApiBaseUrl() + "/r/" + token;
        }
        return new CardContext(film.title(), subtitle, film.thumbnailUrl() == null ? "" : film.thumbnailUrl(), url);
    }

    private String profileUrl(String handle, String industry) {
        String base = showcaseProperties.publicBaseUrl() + "/c/" + handle;
        return industry == null ? base : base + "?industry=" + URLEncoder.encode(industry, StandardCharsets.UTF_8);
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

    /** Template placeholders: {film} (first film's title), {creator}, {industry}, {name}. */
    static String fill(String copy, List<MailableFilm> films, String creatorName, String recipientName) {
        MailableFilm first = films.get(0);
        return copy.replace("{film}", first.title())
                .replace("{creator}", creatorName)
                .replace("{industry}", industry(first).toLowerCase(Locale.ROOT))
                .replace("{name}", recipientName == null || recipientName.isBlank() ? "there" : recipientName.trim());
    }

    private static String greeting(String name) {
        return name == null || name.isBlank() ? "Hi," : "Hi " + name.trim() + ",";
    }

    private static String industry(MailableFilm film) {
        if (film.industry() == null) return "Your industry";
        String n = film.industry().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }
}
