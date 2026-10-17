package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** The stable link to an item's own poster (a redirect to a fresh signed link, see
 * PublicShowcaseService#poster): for a film made here or a video the creator uploaded. Null for a
 * pick from the channel, which shows YouTube's thumbnail. */
@Component
public class ShowcasePosterLinks {

    @Value("${showcase.public-api-base-url:https://api.dalaillama.in/api/v1/public}")
    private String publicApiBaseUrl;

    public String of(ShowcaseItem item) {
        return item.playsOwnCopy() ? publicApiBaseUrl + "/showcase/" + item.getPublicId() + "/poster" : null;
    }
}
