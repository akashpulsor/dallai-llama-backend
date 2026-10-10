package com.dalai.llama.tenant.leadmanagement.audience.provider;

import com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Hunter.io API v2 (https://hunter.io/api-documentation/v2): Discover (find companies, free) and
 * Domain Search (emails at a company, credits). The key goes in the X-API-KEY header. */
@Component
public class HunterClient {

    private static final Duration TIMEOUT = Duration.ofSeconds(20);

    private final HunterProperties properties;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public HunterClient(HunterProperties properties, ObjectMapper json) {
        this.properties = properties;
        this.json = json;
    }

    public record DiscoveredCompany(String domain, String organization, int emailsTotal) {
    }

    public record FoundEmail(String value, String type, int confidence, String firstName, String lastName, String position,
                             String verificationStatus) {
    }

    /** Hunter refused because the account is out of credits or rate-limited. */
    public static class ProviderLimitException extends RuntimeException {
        public ProviderLimitException(String message) {
            super(message);
        }
    }

    public List<DiscoveredCompany> discover(List<String> industries, String countryCode, String query) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (query != null && !query.isBlank()) body.put("query", query.trim());
        if (industries != null && !industries.isEmpty()) body.put("industry", Map.of("include", industries));
        if (countryCode != null && !countryCode.isBlank()) {
            body.put("headquarters_location", Map.of("include", List.of(Map.of("country", countryCode.toUpperCase()))));
        }
        JsonNode response = send(HttpRequest.newBuilder(URI.create(properties.baseUrl() + "/discover"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body))));
        List<DiscoveredCompany> companies = new ArrayList<>();
        response.path("data").forEach(c -> companies.add(new DiscoveredCompany(c.path("domain").asText(),
                c.path("organization").asText(null), c.path("emails_count").path("total").asInt(0))));
        return companies;
    }

    public List<FoundEmail> domainSearch(String domain, int limit) {
        String url = properties.baseUrl() + "/domain-search?domain=" + URLEncoder.encode(domain, StandardCharsets.UTF_8)
                + "&limit=" + limit;
        JsonNode response = send(HttpRequest.newBuilder(URI.create(url)).GET());
        List<FoundEmail> emails = new ArrayList<>();
        response.path("data").path("emails").forEach(e -> emails.add(new FoundEmail(e.path("value").asText(), e.path("type").asText(null),
                e.path("confidence").asInt(0), e.path("first_name").asText(null), e.path("last_name").asText(null),
                e.path("position").asText(null), e.path("verification").path("status").asText(null))));
        return emails;
    }

    private JsonNode send(HttpRequest.Builder request) {
        try {
            HttpResponse<String> r = http.send(request.timeout(TIMEOUT).header("X-API-KEY", properties.apiKey()).build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(r.body() == null || r.body().isBlank() ? "{}" : r.body());
            if (r.statusCode() == 429 || r.statusCode() == 402 || r.statusCode() == 403) {
                throw new ProviderLimitException(firstError(body, "Brand search limit reached; try again later"));
            }
            if (r.statusCode() >= 400) throw new IllegalArgumentException(firstError(body, "The brand search was refused"));
            return body;
        } catch (IOException e) {
            throw new UpstreamUnavailableException("Brand search is unavailable right now", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UpstreamUnavailableException("Brand search was interrupted", e);
        }
    }

    private static String firstError(JsonNode body, String fallback) {
        JsonNode errors = body.path("errors");
        return errors.isArray() && errors.size() > 0 ? errors.get(0).path("details").asText(fallback) : fallback;
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
