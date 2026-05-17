package com.dalai.llama.creator.connector;

import com.dalai.llama.creator.domain.ConnectorCallMethod;

public interface SourceConnectorClient {

    boolean supports(ConnectorCallMethod callMethod);

    ConnectorFetchResult fetch(ConnectorFetchRequest request);
}
