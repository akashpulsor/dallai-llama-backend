UPDATE creator_source_connectors
SET display_name = 'Reddit OAuth Search',
    call_method = 'REDDIT_OAUTH',
    auth_type = 'OAUTH2',
    parser_type = 'JSON',
    base_url = 'https://oauth.reddit.com',
    request_template = '{
        "path": "/search",
        "query": {
            "q": "{{query}}",
            "sort": "hot",
            "t": "day",
            "limit": "25",
            "type": "link"
        }
    }'::jsonb,
    headers_template = headers_template || '{
        "Authorization": "Bearer {{redditAccessToken}}",
        "User-Agent": "{{redditUserAgent}}"
    }'::jsonb,
    requires_api_key = true,
    config_json = config_json || '{
        "oauthGrantType": "client_credentials",
        "tokenUrl": "https://www.reddit.com/api/v1/access_token",
        "credentialEnvironmentVariables": ["REDDIT_CLIENT_ID", "REDDIT_CLIENT_SECRET"],
        "missingCredentialsBehavior": "empty_result"
    }'::jsonb,
    updated_at = now()
WHERE code = 'reddit_public_json';
