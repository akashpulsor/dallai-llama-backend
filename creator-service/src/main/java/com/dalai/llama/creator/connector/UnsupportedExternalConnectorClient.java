package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.ConnectorCallMethod;
import org.springframework.stereotype.Component;

import java.util.EnumSet;
import java.util.Set;

@Component
public class UnsupportedExternalConnectorClient implements SourceConnectorClient {

    private static final Set<ConnectorCallMethod> SUPPORTED_LATER = EnumSet.of(
            ConnectorCallMethod.OFFICIAL_API_KEY,
            ConnectorCallMethod.OFFICIAL_SDK,
            ConnectorCallMethod.HEADLESS_BROWSER,
            ConnectorCallMethod.WEBHOOK,
            ConnectorCallMethod.FILE_IMPORT,
            ConnectorCallMethod.INTERNAL_SERVICE
    );

    @Override
    public boolean supports(ConnectorCallMethod callMethod) {
        return SUPPORTED_LATER.contains(callMethod);
    }

    @Override
    public ConnectorFetchResult fetch(ConnectorFetchRequest request) {
        throw new UnsupportedOperationException(
                "Connector client not implemented yet for call method " + request.connector().getCallMethod()
        );
    }
}
