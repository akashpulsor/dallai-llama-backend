package com.dalai.llama.tenant.youtube.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** The Google OAuth client creators and ops connect YouTube channels with (rule 31), bound from
 * {@code google-oauth.*}. Client id and secret come from the k8s secret; blank = connecting answers
 * 503 "not set up yet". */
@ConfigurationProperties(prefix = "google-oauth")
public record GoogleOAuthProperties(
        String clientId,
        String clientSecret,
        String authUrl,
        String tokenUrl,
        String revokeUrl,
        /* Must match the redirect URI registered on the Google client exactly. */
        String redirectUri,
        List<String> scopes,
        /* Where the browser lands after connecting: creator-ui for creators, the ops page for ops. */
        String creatorReturnUrl,
        String platformReturnUrl,
        int stateMinutes
) {
    public boolean configured() {
        return clientId != null && !clientId.isBlank() && clientSecret != null && !clientSecret.isBlank();
    }
}
