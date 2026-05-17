package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.ConnectorCallMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebRequestConnectorClient implements SourceConnectorClient {

    private static final Pattern YOUTUBE_VIDEO_PATTERN = Pattern.compile(
            "\\\"videoId\\\":\\\"([^\\\"]+)\\\".{0,2500}?\\\"title\\\":\\s*\\{\\s*\\\"runs\\\":\\s*\\[\\s*\\{\\s*\\\"text\\\":\\\"([^\\\"]+)\\\"",
            Pattern.DOTALL
    );

    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public WebRequestConnectorClient(WebClient.Builder webClientBuilder, ObjectMapper objectMapper) {
        this.webClient = webClientBuilder
                .defaultHeader("User-Agent", "DalaiLlamaCreatorBot/1.0")
                .build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ConnectorCallMethod callMethod) {
        return ConnectorCallMethod.WEB_REQUEST == callMethod;
    }

    @Override
    public ConnectorFetchResult fetch(ConnectorFetchRequest request) {
        return switch (request.connector().getCode()) {
            case "google_trends_web" -> fetchGoogleDailyTrends(request);
            case "youtube_public_pages" -> fetchYouTubeSearchPages(request);
            default -> throw new IllegalArgumentException("Unsupported web request connector " + request.connector().getCode());
        };
    }

    private ConnectorFetchResult fetchGoogleDailyTrends(ConnectorFetchRequest request) {
        URI uri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/trends/api/dailytrends", Map.of(
                "hl", "en-US",
                "tz", "-330",
                "geo", request.countryCode(),
                "ns", "15"
        ));
        String body = getBody(uri, request.connector().getTimeoutMs());
        List<String> categoryTerms = ConnectorClientSupport.queryTerms(request, 20);
        List<StructuredTrendSignal> signals = new ArrayList<>();

        try {
            JsonNode root = objectMapper.readTree(ConnectorClientSupport.stripGoogleJsonPrefix(body));
            for (JsonNode day : root.path("default").path("trendingSearchesDays")) {
                for (JsonNode trend : day.path("trendingSearches")) {
                    String query = ConnectorClientSupport.textAt(trend, "/title/query");
                    String traffic = ConnectorClientSupport.text(trend, "formattedTraffic");
                    String articleSnippet = firstArticleSnippet(trend);
                    if (query == null || query.isBlank()) {
                        continue;
                    }
                    if (!matchesCategory(query + " " + articleSnippet, categoryTerms)) {
                        continue;
                    }
                    BigDecimal score = trafficScore(traffic);
                    signals.add(new StructuredTrendSignal(
                            "https://trends.google.com/trends/explore?geo=" + request.countryCode() + "&q=" + ConnectorClientSupport.encode(query),
                            query,
                            articleSnippet,
                            query + " " + nullToEmpty(articleSnippet),
                            "Google Trends",
                            score,
                            score,
                            null,
                            request.windowEndedAt(),
                            ConnectorClientSupport.mapOf("trend", objectMapper.convertValue(trend, Map.class)),
                            ConnectorClientSupport.mapOf("formattedTraffic", traffic, "source", "google_trends_web"),
                            ConnectorClientSupport.dedupe(request.connector().getCode(), request.countryCode(), query)
                    ));
                }
            }
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Google Trends response", ex);
        }

        Map<String, Object> requestSnapshot = ConnectorClientSupport.mapOf("url", uri.toString(), "categoryTerms", categoryTerms);
        Map<String, Object> responseSnapshot = ConnectorClientSupport.mapOf("items", signals.size());
        Map<String, Object> structuredPayload = ConnectorClientSupport.mapOf("signals", signals.size(), "source", "google_trends_web");
        return new ConnectorFetchResult(1, 200, requestSnapshot, responseSnapshot, structuredPayload, signals);
    }

    private ConnectorFetchResult fetchYouTubeSearchPages(ConnectorFetchRequest request) {
        List<String> terms = ConnectorClientSupport.queryTerms(request, 3);
        List<String> urls = new ArrayList<>();
        List<StructuredTrendSignal> signals = new ArrayList<>();

        for (String term : terms) {
            URI uri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/results", Map.of(
                    "search_query", term + " shorts"
            ));
            urls.add(uri.toString());
            String html = getBody(uri, request.connector().getTimeoutMs());
            Matcher matcher = YOUTUBE_VIDEO_PATTERN.matcher(html);
            int rank = 0;
            while (matcher.find() && rank < 20) {
                rank++;
                String videoId = matcher.group(1);
                String title = unescape(matcher.group(2));
                String sourceUrl = "https://www.youtube.com/watch?v=" + videoId;
                BigDecimal score = BigDecimal.valueOf(100L - rank);
                signals.add(new StructuredTrendSignal(
                        sourceUrl,
                        title,
                        "YouTube public search result for " + term + ".",
                        title + " " + term,
                        "YouTube",
                        score,
                        score,
                        null,
                        request.windowEndedAt(),
                        ConnectorClientSupport.mapOf("videoId", videoId, "query", term),
                        ConnectorClientSupport.mapOf("rank", rank, "source", "youtube_public_pages"),
                        ConnectorClientSupport.dedupe(request.connector().getCode(), videoId)
                ));
            }
        }

        Map<String, Object> requestSnapshot = new LinkedHashMap<>();
        requestSnapshot.put("terms", terms);
        requestSnapshot.put("urls", urls);
        Map<String, Object> responseSnapshot = ConnectorClientSupport.mapOf("items", signals.size());
        Map<String, Object> structuredPayload = ConnectorClientSupport.mapOf("signals", signals.size(), "source", "youtube_public_pages");
        return new ConnectorFetchResult(urls.size(), 200, requestSnapshot, responseSnapshot, structuredPayload, signals);
    }

    private String getBody(URI uri, Integer timeoutMs) {
        try {
            return webClient.get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofMillis(timeoutMs == null ? 10000 : timeoutMs));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed web request " + uri, ex);
        }
    }

    private String firstArticleSnippet(JsonNode trend) {
        JsonNode article = trend.path("articles").isArray() && trend.path("articles").size() > 0
                ? trend.path("articles").get(0)
                : null;
        return ConnectorClientSupport.text(article, "snippet");
    }

    private boolean matchesCategory(String value, List<String> terms) {
        String lower = value == null ? "" : value.toLowerCase();
        return terms.stream().anyMatch(term -> lower.contains(term.toLowerCase()));
    }

    private BigDecimal trafficScore(String traffic) {
        if (traffic == null || traffic.isBlank()) {
            return BigDecimal.ONE;
        }
        String normalized = traffic.toLowerCase().replace("+", "").replace(",", "").trim();
        try {
            if (normalized.endsWith("k")) {
                return new BigDecimal(normalized.substring(0, normalized.length() - 1)).multiply(BigDecimal.valueOf(1000));
            }
            if (normalized.endsWith("m")) {
                return new BigDecimal(normalized.substring(0, normalized.length() - 1)).multiply(BigDecimal.valueOf(1_000_000));
            }
            return new BigDecimal(normalized.replaceAll("[^0-9.]", ""));
        } catch (NumberFormatException ex) {
            return BigDecimal.ONE;
        }
    }

    private String unescape(String value) {
        return value == null ? "" : value.replace("\\u0026", "&").replace("\\\"", "\"");
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
