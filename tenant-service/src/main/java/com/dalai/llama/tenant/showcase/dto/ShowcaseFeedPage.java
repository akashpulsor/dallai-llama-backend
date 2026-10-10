package com.dalai.llama.tenant.showcase.dto;

import java.util.List;

/** One page of the public showcase feed. */
public record ShowcaseFeedPage(List<PublicShowcaseCard> items, int page, boolean hasMore) {
}
