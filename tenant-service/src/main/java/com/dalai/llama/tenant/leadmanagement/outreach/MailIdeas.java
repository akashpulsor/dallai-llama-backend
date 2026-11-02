package com.dalai.llama.tenant.leadmanagement.outreach;

import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
import com.dalai.llama.tenant.showcase.inspiration.InspirationPosters;
import com.dalai.llama.tenant.showcase.inspiration.InspirationStore;
import com.dalai.llama.tenant.showcase.inspiration.InspirationStore.MailIdea;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Inspiration videos a creator with no film of their own yet can mail a brand (the INSPIRATION
 * template): ops' picks for the creator's industries first. Only pins with a picture go in mail; a
 * missing picture is fetched once from Pinterest and kept. */
@Component
@RequiredArgsConstructor
public class MailIdeas {

    private final InspirationStore store;
    private final InspirationPosters posters;

    public List<MailIdea> forIndustries(Collection<ShowcaseIndustry> industries, int limit) {
        List<MailIdea> ideas = new ArrayList<>();
        // A few spare, for pins Pinterest has no picture for.
        for (MailIdea idea : store.forMail(List.copyOf(industries), limit * 3)) {
            withPoster(idea).ifPresent(ideas::add);
            if (ideas.size() == limit) break;
        }
        return ideas;
    }

    /** {@code limit} random videos with a picture, all from one industry: {@code industry}, or a random one when null. */
    public List<MailIdea> pick(ShowcaseIndustry industry, int limit) {
        return store.randomForMail(industry, limit);
    }

    /** {@code limit} videos for one mail to {@code email}, picked to fit and to be new to them:
     * <ol><li>ones the address was never sent before ones it was (those longest ago first);</li>
     * <li>among those, the ones sharing the most product themes with {@code topics} (a brand's category,
     * name and website: a jewellery brand gets jewellery videos, from any industry);</li>
     * <li>then ones of {@code industry} (a random industry when null and nothing fits);</li>
     * <li>then at random.</li></ol>
     * With no {@code topics}, the videos all come from {@code industry} (or one random industry). */
    public List<MailIdea> forRecipient(ShowcaseIndustry industry, String topics, String email, int limit) {
        java.util.Set<String> wanted = MailThemes.of(topics);
        List<InspirationStore.SeenIdea> all = store.forMailTo(email);
        if (all.isEmpty()) return List.of();
        java.util.Map<UUID, Integer> fit = new java.util.HashMap<>();
        for (var s : all) fit.put(s.idea().id(), MailThemes.overlap(wanted, MailThemes.of(s.idea().title())));
        boolean anyFit = fit.values().stream().anyMatch(n -> n > 0);
        ShowcaseIndustry home = industry != null || anyFit ? industry
                : all.get(java.util.concurrent.ThreadLocalRandom.current().nextInt(all.size())).idea().industry();
        List<InspirationStore.SeenIdea> pool = all.stream()
                .filter(s -> anyFit ? fit.get(s.idea().id()) > 0 || s.idea().industry() == home : s.idea().industry() == home)
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        java.util.Collections.shuffle(pool);
        pool.sort(java.util.Comparator
                .comparing((InspirationStore.SeenIdea s) -> s.lastSent() != null)
                .thenComparing(s -> s.lastSent() == null ? java.time.Instant.MIN : s.lastSent())
                .thenComparing(s -> -fit.get(s.idea().id()))
                .thenComparing(s -> s.idea().industry() == home ? 0 : 1));
        return pool.stream().limit(limit).map(InspirationStore.SeenIdea::idea).toList();
    }

    /** Remembers which videos a mail to {@code email} carried. */
    public void recordSent(String email, List<MailIdea> ideas, java.time.Instant at) {
        if (email != null && !ideas.isEmpty()) store.recordSent(email, ideas.stream().map(MailIdea::id).toList(), at);
    }

    private Optional<MailIdea> withPoster(MailIdea idea) {
        if (idea.posterUrl() != null) return Optional.of(idea);
        return posters.fetch(idea.pinId()).map(poster -> {
            store.setPoster(idea.id(), poster);
            return new MailIdea(idea.id(), idea.pinId(), idea.pinUrl(), idea.title(), idea.industry(), poster.url(),
                    poster.width(), poster.height(), poster.makerName());
        });
    }
}
