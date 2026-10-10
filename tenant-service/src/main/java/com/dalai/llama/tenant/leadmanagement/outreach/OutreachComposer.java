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

    /** A digest line: the film, who made it, and why it's in this mail. */
    public record DigestCard(MailableFilm film, OutreachTemplate template) {
    }

    record CreatorMailContext(String greeting, String intro, String note, String filmsHtml, String filmsText,
                              String creatorName, String profileUrl, String unsubscribeUrl) {
    }

    record DigestContext(String greeting, String intro, String cardsHtml, String cardsText, String discoverUrl,
                         String unsubscribeUrl) {
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
        CreatorMailContext context = new CreatorMailContext(greeting(recipientName),
                fill(template.intro(), films, creatorName, recipientName), blank(note),
                html.toString(), text.toString(), creatorName, profileUrl, unsubscribe);
        return new Mail(fill(template.subject(), films, creatorName, recipientName), renderer.text("creator_mail", context),
                renderer.html("creator_mail", context), unsubscribeHeaders(unsubscribe));
    }

    public Mail digest(List<DigestCard> cards, String recipientName, OutreachStore.Delivery delivery) {
        StringBuilder html = new StringBuilder();
        StringBuilder text = new StringBuilder();
        for (DigestCard c : cards) {
            String why = c.template() == OutreachTemplate.NEW_FILM
                    ? "New from " + c.film().creatorName() + ", who you follow"
                    : "By " + c.film().creatorName() + " · " + industry(c.film());
            CardContext card = card(c.film(), why, delivery);
            html.append(renderer.html("card", card));
            text.append(renderer.text("card", card));
        }
        String unsubscribe = unsubscribeUrl(delivery);
        DigestContext context = new DigestContext(greeting(recipientName),
                cards.size() == 1 ? "A film a creator wanted you to see:" : "Films creators wanted you to see today:",
                html.toString(), text.toString(), showcaseProperties.publicBaseUrl() + "/creators", unsubscribe);
        String subject = cards.size() == 1 ? cards.get(0).film().title() + " · from " + cards.get(0).film().creatorName()
                : cards.size() + " films from creators on Dalai Llama";
        return new Mail(subject, renderer.text("digest", context), renderer.html("digest", context), unsubscribeHeaders(unsubscribe));
    }

    private CardContext card(MailableFilm film, String subtitle, OutreachStore.Delivery delivery) {
        String target = profileUrl(film.handle(), film.industry().name());
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
        String n = film.industry().name().replace('_', ' ').toLowerCase(Locale.ROOT);
        return Character.toUpperCase(n.charAt(0)) + n.substring(1);
    }

    private static String blank(String s) {
        return s == null ? "" : s.trim();
    }
}
