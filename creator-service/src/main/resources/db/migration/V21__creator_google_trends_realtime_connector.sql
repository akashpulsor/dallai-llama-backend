UPDATE creator_source_connectors
SET request_template = '{
        "path": "/trends/api/realtimetrends",
        "fallbackPath": "/trends/api/dailytrends",
        "query": {
            "hl": "en-US",
            "tz": "-330",
            "cat": "all",
            "fi": "0",
            "fs": "0",
            "geo": "{{countryCode}}",
            "ri": "300",
            "rs": "20",
            "sort": "0"
        }
    }'::jsonb,
    headers_template = headers_template || '{
        "User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36",
        "Accept": "application/json,text/plain,*/*",
        "Accept-Language": "en-US,en;q=0.9"
    }'::jsonb,
    parser_type = 'JSON',
    config_json = config_json || '{
        "primaryEndpoint": "google_trends_realtime",
        "fallbackEndpoint": "google_trends_daily",
        "notFoundIsEmptyResult": true
    }'::jsonb,
    updated_at = now()
WHERE code = 'google_trends_web';
