package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.ConnectorCallMethod;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class SourceConnectorClientRegistry {

    private final List<SourceConnectorClient> clients;

    public SourceConnectorClientRegistry(List<SourceConnectorClient> clients) {
        this.clients = clients;
    }

    public SourceConnectorClient resolve(ConnectorCallMethod callMethod) {
        return clients.stream()
                .filter(client -> client.supports(callMethod))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("No connector client registered for " + callMethod));
    }
}
