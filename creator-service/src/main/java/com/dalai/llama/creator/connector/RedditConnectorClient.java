package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.config.CreatorProperties;
import com.dalai.llama.creator.domain.ConnectorCallMethod;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RedditConnectorClient implements SourceConnectorClient {

    private static final Logger log = LoggerFactory.getLogger(RedditConnectorClient.class);

    private final CreatorProperties properties;
    private final RedditOAuthTokenProvider tokenProvider;
    private final WebClient webClient;
    private final ObjectMapper objectMapper;

    public RedditConnectorClient(
            CreatorProperties properties,
            RedditOAuthTokenProvider tokenProvider,
            WebClient.Builder webClientBuilder,
            ObjectMapper objectMapper
    ) {
        this.properties = properties;
        this.tokenProvider = tokenProvider;
        this.webClient = webClientBuilder.build();
        this.objectMapper = objectMapper;
    }

    @Override
    public boolean supports(ConnectorCallMethod callMethod) {
        return ConnectorCallMethod.REDDIT_OAUTH == callMethod;
    }

    @Override
    public ConnectorFetchResult fetch(ConnectorFetchRequest request) {
        if (!"reddit_public_json".equals(request.connector().getCode())
                && !"reddit_oauth".equals(request.connector().getCode())) {
            throw new IllegalArgumentException("Unsupported Reddit OAuth connector " + request.connector().getCode());
        }

        if (!tokenProvider.hasCredentials()) {
            log.warn(
                    "Reddit OAuth connector skipped because credentials are missing connector={} targetPlatform={} category={} country={}",
                    request.connector().getCode(),
                    request.targetPlatformCode(),
                    request.category().getCode(),
                    request.countryCode()
            );
            return emptyResult(request, "missing_reddit_oauth_credentials");
        }

        List<String> terms = ConnectorClientSupport.queryTerms(request, 3);
        List<String> urls = new ArrayList<>();
        List<StructuredTrendSignal> signals = new ArrayList<>();
        int requestCount = 0;
        int lastStatus = 200;

        for (String term : terms) {
            URI uri = ConnectorClientSupport.uri(redditApiBaseUrl(), "/search", Map.of(
                    "q", term,
                    "sort", "hot",
                    "t", "day",
                    "limit", "25",
                    "type", "link"
            ));
            urls.add(uri.toString());
            RedditResponse response = getJsonWithRefresh(uri, request.connector().getTimeoutMs());
            requestCount += response.requestCount();
            lastStatus = response.statusCode();

            for (JsonNode child : response.root().at("/data/children")) {
                JsonNode data = child.get("data");
                StructuredTrendSignal signal = toSignal(request, term, data);
                if (signal != null) {
                    signals.add(signal);
                }
            }
        }

        return result(request, terms, urls, requestCount, lastStatus, ConnectorClientSupport.topSignals(signals, 25));
    }

    private StructuredTrendSignal toSignal(ConnectorFetchRequest request, String term, JsonNode data) {
        String title = ConnectorClientSupport.text(data, "title");
        if (title == null || title.isBlank()) {
            return null;
        }

        String selfText = ConnectorClientSupport.text(data, "selftext");
        String permalink = ConnectorClientSupport.text(data, "permalink");
        String sourceUrl = permalink == null ? ConnectorClientSupport.text(data, "url") : "https://www.reddit.com" + permalink;
        BigDecimal score = ConnectorClientSupport.decimal(data, "score");
        BigDecimal comments = ConnectorClientSupport.decimal(data, "num_comments");
        BigDecimal upvoteRatio = ConnectorClientSupport.decimal(data, "upvote_ratio");
        BigDecimal rankScore = score.add(comments.multiply(BigDecimal.valueOf(2))).add(upvoteRatio.multiply(BigDecimal.TEN));

        return new StructuredTrendSignal(
                sourceUrl,
                title,
                selfText,
                title + " " + nullToEmpty(selfText),
                ConnectorClientSupport.text(data, "subreddit_name_prefixed"),
                rankScore,
                rankScore,
                ConnectorClientSupport.epochSeconds(data, "created_utc"),
                request.windowEndedAt(),
                ConnectorClientSupport.mapOf("reddit", objectMapper.convertValue(data, Map.class), "query", term),
                ConnectorClientSupport.mapOf(
                        "score", score,
                        "comments", comments,
                        "upvoteRatio", upvoteRatio,
                        "source", "reddit_oauth"
                ),
                ConnectorClientSupport.dedupe(request.connector().getCode(), sourceUrl, title)
        );
    }

    private RedditResponse getJsonWithRefresh(URI uri, Integer timeoutMs) {
        RedditTextResponse response = getText(uri, timeoutMs, tokenProvider.getAccessToken());
        int requestCount = 1;
        if (response.statusCode() == HttpStatus.UNAUTHORIZED.value()) {
            tokenProvider.invalidate();
            response = getText(uri, timeoutMs, tokenProvider.getAccessToken());
            requestCount++;
        }

        if (!response.successful()) {
            throw new IllegalStateException("Failed Reddit OAuth request " + uri + " HTTP " + response.statusCode());
        }

        try {
            return new RedditResponse(response.statusCode(), requestCount, objectMapper.readTree(response.body()));
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse Reddit OAuth response " + uri, ex);
        }
    }

    private RedditTextResponse getText(URI uri, Integer timeoutMs, String accessToken) {
        try {
            RedditTextResponse response = webClient.get()
                    .uri(uri)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken)
                    .header(HttpHeaders.USER_AGENT, properties.getConnectors().getReddit().getUserAgent())
                    .retrieve()
                    .toEntity(String.class)
                    .map(entity -> new RedditTextResponse(entity.getStatusCode().value(), entity.getBody() == null ? "" : entity.getBody()))
                    .block(Duration.ofMillis(timeoutMs == null ? 10000 : timeoutMs));
            if (response == null) {
                throw new IllegalStateException("No Reddit response returned");
            }
            return response;
        } catch (org.springframework.web.reactive.function.client.WebClientResponseException ex) {
            return new RedditTextResponse(ex.getStatusCode().value(), ex.getResponseBodyAsString());
        } catch (Exception ex) {
            throw new IllegalStateException("Failed Reddit OAuth request " + uri, ex);
        }
    }

    private ConnectorFetchResult result(
            ConnectorFetchRequest request,
            List<String> terms,
            List<String> urls,
            int requestCount,
            int httpStatus,
            List<StructuredTrendSignal> signals
    ) {
        Map<String, Object> requestSnapshot = new LinkedHashMap<>();
        requestSnapshot.put("terms", terms);
        requestSnapshot.put("urls", urls);

        Map<String, Object> responseSnapshot = new LinkedHashMap<>();
        responseSnapshot.put("items", signals.size());

        Map<String, Object> structuredPayload = new LinkedHashMap<>();
        structuredPayload.put("connector", request.connector().getCode());
        structuredPayload.put("signals", signals.size());
        structuredPayload.put("source", "reddit_oauth");
        structuredPayload.put("windowEndedAt", OffsetDateTime.now().toString());

        return new ConnectorFetchResult(requestCount, httpStatus, requestSnapshot, responseSnapshot, structuredPayload, signals);
    }

    private ConnectorFetchResult emptyResult(ConnectorFetchRequest request, String reason) {
        return new ConnectorFetchResult(
                0,
                null,
                ConnectorClientSupport.mapOf("reason", reason),
                ConnectorClientSupport.mapOf("items", 0),
                ConnectorClientSupport.mapOf("connector", request.connector().getCode(), "signals", 0, "source", "reddit_oauth", "reason", reason),
                List.of()
        );
    }

    private String redditApiBaseUrl() {
        String configured = properties.getConnectors().getReddit().getApiBaseUrl();
        return configured == null || configured.isBlank() ? "https://oauth.reddit.com" : configured;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private record RedditTextResponse(int statusCode, String body) {
        private boolean successful() {
            return statusCode >= 200 && statusCode < 300;
        }
    }

    private record RedditResponse(int statusCode, int requestCount, JsonNode root) {
    }
}
