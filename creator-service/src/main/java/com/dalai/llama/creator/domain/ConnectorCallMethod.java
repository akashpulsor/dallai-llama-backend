package com.dalai.llama.creator.domain;

public enum ConnectorCallMethod {
    TARGET_ONLY,
    WEB_REQUEST,
    PUBLIC_API_NO_KEY,
    REDDIT_OAUTH,
    RSS_FEED,
    OFFICIAL_API_KEY,
    OFFICIAL_SDK,
    HEADLESS_BROWSER,
    WEBHOOK,
    FILE_IMPORT,
    INTERNAL_SERVICE,
    MOCK
}
