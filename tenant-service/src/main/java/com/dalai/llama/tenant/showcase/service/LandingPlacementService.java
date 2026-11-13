package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.domain.CreatorLevel;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.dto.LandingPlacementView;
import com.dalai.llama.tenant.showcase.dto.PublicShowcaseCard;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.stream.IntStream;

/** Ops placing creators' films on the landing page only: off it, or into the hero at the top. The
 * film's profile, feeds and score are untouched; {@link LandingAssembler} reads the placement. */
@Service
@RequiredArgsConstructor
public class LandingPlacementService {

    /** Far more films than there are today; the ops list is everything that may be shown. */
    private static final int LIST_LIMIT = 200;

    private final PublicShowcaseService showcase;
    private final ShowcaseItemRepository itemRepository;
    private final Clock clock;

    /** Every film that may be shown publicly, best score first, with its landing placement. */
    @Transactional(readOnly = true)
    public List<LandingPlacementView> list() {
        List<ShowcaseItem> items = showcase.rankedPublic(EnumSet.allOf(CreatorLevel.class), null, LIST_LIMIT);
        List<PublicShowcaseCard> cards = showcase.toCards(items);
        return IntStream.range(0, items.size()).mapToObj(n -> LandingPlacementView.of(items.get(n), cards.get(n))).toList();
    }

    /** Takes a film off the landing page or puts it back, and in or out of the hero. Staying a hero
     * keeps its place in the hero order. */
    @Transactional
    public LandingPlacementView place(String publicId, boolean hidden, boolean hero) {
        if (hidden && hero) {
            throw new IllegalArgumentException("A hero video can't also be taken off the landing page");
        }
        ShowcaseItem item = itemRepository.findByPublicId(publicId)
                .orElseThrow(() -> new IllegalArgumentException("No item " + publicId));
        item.setLandingHidden(hidden);
        if (!hero) {
            item.setLandingHeroAt(null);
        } else if (item.getLandingHeroAt() == null) {
            item.setLandingHeroAt(OffsetDateTime.now(clock));
        }
        ShowcaseItem saved = itemRepository.save(item);
        return LandingPlacementView.of(saved, showcase.toCards(List.of(saved)).get(0));
    }
}
