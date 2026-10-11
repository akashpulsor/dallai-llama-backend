package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Apollo.io API (https://docs.apollo.io): People API Search (no credits, no emails) and People
 * Enrichment / match (1 credit when an email is returned). The key goes in the x-api-key header. */
@Component
public class ApolloClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final ApolloProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public ApolloClient(ApolloProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public record Person(String id, String firstName, String lastNameHint, String title, String companyName, String companyDomain,
                         boolean hasEmail) {
    }

    public record Enriched(String email, String emailStatus, String firstName, String lastName, String title, String companyName,
                           String companyDomain) {
    }

    public record Organization(String name, String domain, String industry) {
    }

    /** Organization search -- available on Apollo's free plan (people and emails are not). */
    public List<Organization> searchOrganizations(List<String> keywordTags, String location, int perPage) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (keywordTags != null && !keywordTags.isEmpty()) body.put("q_organization_keyword_tags", keywordTags);
        if (location != null && !location.isBlank()) body.put("organization_locations", List.of(location));
        body.put("page", 1);
        body.put("per_page", perPage);
        JsonNode response = post("/organizations/search", body);
        List<Organization> orgs = new ArrayList<>();
        for (String field : List.of("organizations", "accounts")) {
            response.path(field).forEach(o -> {
                String domain = domain(o);
                if (domain != null && !domain.isBlank()) orgs.add(new Organization(text(o, "name"), domain, text(o, "industry")));
            });
        }
        return orgs;
    }

    public List<Person> searchPeople(List<String> titles, String keywords, String location, int perPage) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (titles != null && !titles.isEmpty()) body.put("person_titles", titles);
        if (keywords != null && !keywords.isBlank()) body.put("q_keywords", keywords);
        if (location != null && !location.isBlank()) body.put("organization_locations", List.of(location));
        body.put("page", 1);
        body.put("per_page", perPage);
        JsonNode response = post("/mixed_people/api_search", body);
        List<Person> people = new ArrayList<>();
        response.path("people").forEach(p -> {
            JsonNode org = p.path("organization");
            people.add(new Person(p.path("id").asText(), text(p, "first_name"), lastName(p), text(p, "title"), text(org, "name"),
                    domain(org), p.path("has_email").asBoolean(false)));
        });
        return people;
    }

    /** The person's work email, if Apollo has one. Empty when Apollo found nothing (no credit spent). */
    public Optional<Enriched> reveal(String personId) {
        JsonNode p = post("/people/match", Map.of("id", personId, "reveal_personal_emails", false)).path("person");
        if (p.isMissingNode() || p.isNull()) return Optional.empty();
        JsonNode org = p.path("organization");
        return Optional.of(new Enriched(text(p, "email"), text(p, "email_status"), text(p, "first_name"), text(p, "last_name"),
                text(p, "title"), text(org, "name"), domain(org)));
    }

    private JsonNode post(String path, Object body) {
        try {
            HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(properties.baseUrl() + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .header("Cache-Control", "no-cache")
                    .header("x-api-key", properties.apiKey())
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)))
                    .build(), HttpResponse.BodyHandlers.ofString());
            JsonNode node = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
            if (r.statusCode() == 429 || r.statusCode() == 402) {
                throw new HunterClient.ProviderLimitException("People search limit reached; try again later");
            }
            if (r.statusCode() == 401 || r.statusCode() == 403) {
                throw new HunterClient.ProviderLimitException(node.path("error").asText("Apollo refused our key; people search isn't available on this plan"));
            }
            if (r.statusCode() >= 400) throw new IllegalArgumentException(node.path("error").asText("The people search was refused"));
            return node;
        } catch (IOException e) {
            throw new UpstreamUnavailableException("People search is unavailable right now", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("People search was interrupted", e);
        }
    }

    private static String lastName(JsonNode p) {
        String full = text(p, "last_name");
        return full != null ? full : text(p, "last_name_obfuscated");
    }

    private static String domain(JsonNode org) {
        String d = text(org, "primary_domain");
        if (d != null) return d.toLowerCase();
        String site = text(org, "website_url");
        return site == null ? null : site.replaceFirst("^https?://(www\\.)?", "").replaceFirst("/.*$", "").toLowerCase();
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() || v.asText().isBlank() ? null : v.asText();
    }
}
