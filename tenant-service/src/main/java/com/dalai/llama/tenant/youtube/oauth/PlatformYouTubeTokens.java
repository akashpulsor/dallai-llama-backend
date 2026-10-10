package com.dalai.llama.tenant.youtube.oauth;

import java.util.Optional;

/** An access token for the official Dalaillama channel when ops connected it with OAuth (rule 33).
 * Empty = fall back to the configured refresh-token secret. */
public interface PlatformYouTubeTokens {

    Optional<String> platformAccessToken();
}
