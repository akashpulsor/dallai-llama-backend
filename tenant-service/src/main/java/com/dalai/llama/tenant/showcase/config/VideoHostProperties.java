package com.dalai.llama.tenant.showcase.config;

import com.dalai.llama.tenant.showcase.domain.VideoHostType;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Where showcase video plays from, and Dalaillama's own YouTube channel (rules 3 and 5). Bound
 * from {@code video-host.*}. */
@ConfigurationProperties(prefix = "video-host")
public record VideoHostProperties(
        /* YOUTUBE normally; SELF moves every platform film to our own copy in one switch. */
        VideoHostType platformFilms,
        OfficialChannel officialChannel
) {

    public boolean platformFilmsSelfHosted() {
        return platformFilms == VideoHostType.SELF;
    }

    /** OAuth client from a Google Cloud project owned by our Workspace, consent screen Internal
     * (no Google verification needed), authorised once by ops for our channel only. */
    public record OfficialChannel(
            boolean enabled,
            String clientId,
            String clientSecret,
            String refreshToken,
            String tokenUrl,
            String uploadUrl,
            /* public or unlisted */
            String privacy,
            /* "{creatorName}" and "{profileUrl}" are filled in. */
            String descriptionTemplate,
            /* Ask the creator to require client consent and verified funding before uploading. */
            boolean requireFundedAndConsent
    ) {
        public boolean configured() {
            return enabled && notBlank(clientId) && notBlank(clientSecret) && notBlank(refreshToken);
        }

        private static boolean notBlank(String s) {
            return s != null && !s.isBlank();
        }
    }
}
