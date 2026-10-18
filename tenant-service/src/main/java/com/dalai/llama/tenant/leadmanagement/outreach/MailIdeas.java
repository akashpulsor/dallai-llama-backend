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

    private Optional<MailIdea> withPoster(MailIdea idea) {
        if (idea.posterUrl() != null) return Optional.of(idea);
        return posters.fetch(idea.pinId()).map(poster -> {
            store.setPoster(idea.id(), poster);
            return new MailIdea(idea.id(), idea.pinId(), idea.pinUrl(), idea.title(), idea.industry(), poster.url(),
                    poster.width(), poster.height(), poster.makerName());
        });
    }
}
