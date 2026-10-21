package com.dalai.llama.tenant.leadmanagement.outreach;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Each open of an outreach mail (our open pixel) is also an event in Google Analytics, on the
 * landing site's GA4 property, named for what was opened -- {@code email_open_showcase_work},
 * {@code email_open_similar_brand_work}, {@code email_open_creator_portfolio},
 * {@code email_open_inspiration}, {@code email_open_digest} -- so each template is its own event. An inbox runs no
 * scripts, so the event goes server to server: through the Measurement Protocol when an API secret
 * is set (GA Admin, Data streams, Measurement Protocol API secrets), else as a plain collect hit.
 * The GA user is the recipient (a short prefix of their hash, never the address), so repeat opens
 * by one person count as one user. Fire and forget: GA being slow or down never delays the pixel.
 */
@Slf4j
@Component
public class OpenAnalytics {

    private static final Duration TIMEOUT = Duration.ofSeconds(5);

    private final ObjectMapper json;
    private final String measurementId;
    private final String apiSecret;
    private final String endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

    public OpenAnalytics(ObjectMapper json,
                         @Value("${outreach.analytics.measurement-id:}") String measurementId,
                         @Value("${outreach.analytics.api-secret:}") String apiSecret,
                         @Value("${outreach.analytics.endpoint:https://www.google-analytics.com}") String endpoint) {
        this.json = json;
        this.measurementId = measurementId;
        this.apiSecret = apiSecret;
        this.endpoint = endpoint;
    }

    public void opened(OutreachStore.Opened open) {
        if (measurementId == null || measurementId.isBlank()) return;
        try {
            http.sendAsync(request(open), HttpResponse.BodyHandlers.discarding())
                    .whenComplete((response, error) -> {
                        if (error != null) log.debug("GA email_open not sent: {}", error.getMessage());
                        else if (response.statusCode() >= 300) log.debug("GA email_open answered {}", response.statusCode());
                    });
        } catch (Exception e) {
            log.debug("GA email_open not sent: {}", e.getMessage());
        }
    }

    HttpRequest request(OutreachStore.Opened open) throws Exception {
        String clientId = "rcpt." + open.recipientHash().substring(0, 16);
        String name = eventName(open);
        Map<String, Object> params = params(open);
        if (apiSecret != null && !apiSecret.isBlank()) {
            Map<String, Object> event = Map.of("name", name, "params", params);
            String body = json.writeValueAsString(Map.of("client_id", clientId, "events", List.of(event)));
            return HttpRequest.newBuilder(URI.create(endpoint + "/mp/collect?measurement_id=" + encode(measurementId)
                            + "&api_secret=" + encode(apiSecret)))
                    .timeout(TIMEOUT).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
        }
        StringBuilder url = new StringBuilder(endpoint).append("/g/collect?v=2&tid=").append(encode(measurementId))
                .append("&cid=").append(encode(clientId)).append("&en=").append(encode(name));
        params.forEach((k, v) -> url.append(v instanceof Number ? "&epn." : "&ep.").append(encode(k)).append('=').append(encode(String.valueOf(v))));
        return HttpRequest.newBuilder(URI.create(url.toString())).timeout(TIMEOUT)
                .header("User-Agent", "Mozilla/5.0 (compatible; DalaiLlamaMail/1.0)").POST(HttpRequest.BodyPublishers.noBody()).build();
    }

    /** email_open_<template> for a creator's mail, email_open_digest for the merged mail. */
    static String eventName(OutreachStore.Opened open) {
        String what = "DIGEST".equals(open.kind()) || open.template() == null ? "digest" : open.template();
        return "email_open_" + what.toLowerCase(java.util.Locale.ROOT);
    }

    /** mail_kind: CREATOR_MAIL or DIGEST; template: the creator's template (none for a digest). */
    private static Map<String, Object> params(OutreachStore.Opened open) {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("mail_kind", open.kind());
        if (open.template() != null) params.put("template", open.template());
        params.put("first_open", open.firstOpen() ? 1 : 0);
        params.put("engagement_time_msec", 1);
        return params;
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
