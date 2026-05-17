package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.ConnectorCallMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class RssFeedConnectorClient implements SourceConnectorClient {

    private final WebClient webClient;

    public RssFeedConnectorClient(WebClient.Builder webClientBuilder) {
        this.webClient = webClientBuilder
                .defaultHeader("User-Agent", "DalaiLlamaCreatorBot/1.0")
                .build();
    }

    @Override
    public boolean supports(ConnectorCallMethod callMethod) {
        return ConnectorCallMethod.RSS_FEED == callMethod;
    }

    @Override
    public ConnectorFetchResult fetch(ConnectorFetchRequest request) {
        if (!"reddit_rss".equals(request.connector().getCode())) {
            throw new IllegalArgumentException("Unsupported RSS connector " + request.connector().getCode());
        }

        List<String> terms = ConnectorClientSupport.queryTerms(request, 3);
        List<String> urls = new ArrayList<>();
        List<StructuredTrendSignal> signals = new ArrayList<>();

        for (String term : terms) {
            URI uri = ConnectorClientSupport.uri(request.connector().getBaseUrl(), "/search.rss", Map.of(
                    "q", term,
                    "sort", "hot",
                    "t", "day"
            ));
            urls.add(uri.toString());
            String xml = getBody(uri, request.connector().getTimeoutMs());
            signals.addAll(parseRss(request, term, xml));
        }

        Map<String, Object> requestSnapshot = new LinkedHashMap<>();
        requestSnapshot.put("terms", terms);
        requestSnapshot.put("urls", urls);

        Map<String, Object> responseSnapshot = new LinkedHashMap<>();
        responseSnapshot.put("items", signals.size());

        Map<String, Object> structuredPayload = new LinkedHashMap<>();
        structuredPayload.put("signals", signals.size());
        structuredPayload.put("source", "reddit_rss");

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
            throw new IllegalStateException("Failed RSS request " + uri, ex);
        }
    }

    private List<StructuredTrendSignal> parseRss(ConnectorFetchRequest request, String term, String xml) {
        try {
            Document document = DocumentBuilderFactory.newInstance()
                    .newDocumentBuilder()
                    .parse(new InputSource(new StringReader(xml)));
            NodeList entries = document.getElementsByTagName("entry");
            if (entries.getLength() == 0) {
                entries = document.getElementsByTagName("item");
            }

            List<StructuredTrendSignal> signals = new ArrayList<>();
            for (int index = 0; index < entries.getLength(); index++) {
                Element entry = (Element) entries.item(index);
                String title = text(entry, "title");
                String link = link(entry);
                String summary = firstNonBlank(text(entry, "summary"), text(entry, "description"));
                if (title == null || title.isBlank()) {
                    continue;
                }

                BigDecimal score = BigDecimal.valueOf(Math.max(1, entries.getLength() - index));
                signals.add(new StructuredTrendSignal(
                        link,
                        title,
                        summary,
                        title + " " + nullToEmpty(summary),
                        text(entry, "author"),
                        score,
                        score,
                        null,
                        request.windowEndedAt(),
                        ConnectorClientSupport.mapOf("term", term, "title", title, "link", link),
                        ConnectorClientSupport.mapOf("rank", index + 1, "source", "reddit_rss"),
                        ConnectorClientSupport.dedupe(request.connector().getCode(), link, title)
                ));
            }
            return signals;
        } catch (Exception ex) {
            throw new IllegalStateException("Failed to parse RSS response", ex);
        }
    }

    private String text(Element element, String tagName) {
        NodeList nodes = element.getElementsByTagName(tagName);
        if (nodes.getLength() == 0) {
            return null;
        }
        return nodes.item(0).getTextContent();
    }

    private String link(Element element) {
        NodeList links = element.getElementsByTagName("link");
        if (links.getLength() == 0) {
            return null;
        }
        Element link = (Element) links.item(0);
        String href = link.getAttribute("href");
        return href == null || href.isBlank() ? link.getTextContent() : href;
    }

    private String firstNonBlank(String first, String second) {
        return first == null || first.isBlank() ? second : first;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
