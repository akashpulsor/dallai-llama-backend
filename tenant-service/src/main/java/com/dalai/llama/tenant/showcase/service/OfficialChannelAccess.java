package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.config.VideoHostProperties;
import com.dalai.llama.tenant.youtube.oauth.PlatformYouTubeTokens;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/** Whether Dalaillama's own channel can take uploads: ops connected it with Google (rule 33), or
 * the legacy refresh-token secret is set. Connecting it is what switches the official uploads on. */
@Component
@RequiredArgsConstructor
public class OfficialChannelAccess {

    private final VideoHostProperties hostProperties;
    private final ObjectProvider<PlatformYouTubeTokens> platformTokens;

    public boolean ready() {
        PlatformYouTubeTokens tokens = platformTokens.getIfAvailable();
        return (tokens != null && tokens.platformConnected()) || hostProperties.officialChannel().configured();
    }
}
