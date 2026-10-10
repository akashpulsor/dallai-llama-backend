package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.brand.BrandContactRepository;
import com.dalai.llama.tenant.leadmanagement.brand.BrandNotSignedInException;
import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.BrandInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.CreatorInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmitInquiryRequest;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmittedInquiry;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseItemStatus;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.domain.entity.ShowcaseItem;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import com.dalai.llama.tenant.showcase.repository.ShowcaseItemRepository;
import com.dalai.llama.tenant.youtube.domain.entity.YouTubeVideo;
import com.dalai.llama.tenant.youtube.repository.YouTubeVideoRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/** "Request a video": a signed-in brand asks a creator; the creator reads it, declines it, or turns
 * it into a brief, which mails the brand the brief link and hands over to the existing brief flow. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandInquiryService {

    /** Requests one brand can send per day, across all creators: enough for a real search, too few
     * to spam with. */
    static final int MAX_REQUESTS_PER_BRAND_PER_DAY = 10;

    private final BrandInquiryRepository inquiryRepository;
    private final BrandContactRepository contactRepository;
    private final CreatorPublicProfileRepository profileRepository;
    private final ShowcaseItemRepository itemRepository;
    private final YouTubeVideoRepository videoRepository;
    private final TenantRepository tenantRepository;
    private final CreativePlanningBriefClient briefClient;
    private final PlatformMailer mailer;
    private final BrandProperties brandProperties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public SubmittedInquiry submit(UUID brandContactId, String handle, SubmitInquiryRequest request) {
        BrandContact brand = contactRepository.findById(brandContactId).orElseThrow(BrandNotSignedInException::new);
        CreatorPublicProfile creator = profileRepository.findByHandle(handle)
                .filter(p -> p.getStatus() == ProfileStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown creator"));
        Integer today = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lead_brand_inquiry WHERE brand_contact_id = ? AND created_at > ?", Integer.class,
                brandContactId, Timestamp.from(OffsetDateTime.now(clock).minusDays(1).toInstant()));
        if (today != null && today >= MAX_REQUESTS_PER_BRAND_PER_DAY) {
            throw new IllegalStateException("You've sent a lot of requests today; please try again tomorrow");
        }
        // Only a live film of this creator counts as "the film they were watching".
        Optional<ShowcaseItem> film = Optional.ofNullable(request.publicId())
                .flatMap(itemRepository::findByPublicId)
                .filter(i -> i.getTenantId().equals(creator.getTenantId()) && i.getStatus() == ShowcaseItemStatus.LIVE);

        BrandInquiry inquiry = inquiryRepository.save(BrandInquiry.builder()
                .id(UUID.randomUUID())
                .tenantId(creator.getTenantId())
                .brandContactId(brandContactId)
                .showcaseItemId(film.map(ShowcaseItem::getId).orElse(null))
                .attributionToken(request.attributionToken())
                .budgetBand(request.budgetBand())
                .timeline(request.timeline())
                .message(request.message().trim())
                .status(BrandInquiry.Status.NEW)
                .build());
        // Brand interest is a ranking signal for the film (rule 13).
        film.ifPresent(i -> jdbc.update("UPDATE showcase_item SET inquiry_count = inquiry_count + 1 WHERE id = ?", i.getId()));
        notifyCreator(creator, brand, inquiry);
        return new SubmittedInquiry(inquiry.getId(), inquiry.getStatus());
    }

    public List<BrandInquiryView> forBrand(UUID brandContactId) {
        List<BrandInquiry> inquiries = inquiryRepository.findByBrandContactIdOrderByCreatedAtDesc(brandContactId);
        Map<UUID, CreatorPublicProfile> creators = profileRepository.findAllById(
                        inquiries.stream().map(BrandInquiry::getTenantId).distinct().toList()).stream()
                .collect(Collectors.toMap(CreatorPublicProfile::getTenantId, Function.identity()));
        Map<UUID, String> titles = filmTitles(inquiries);
        return inquiries.stream().map(i -> {
            CreatorPublicProfile p = creators.get(i.getTenantId());
            return new BrandInquiryView(i.getId(), p.getHandle(), p.getDisplayName(), p.getAvatarUrl(),
                    titles.get(i.getShowcaseItemId()), i.getBudgetBand(), i.getTimeline(), i.getMessage(), i.getStatus(),
                    briefUrl(i), i.getCreatedAt());
        }).toList();
    }

    public List<CreatorInquiryView> forCreator(UUID tenantId) {
        List<BrandInquiry> inquiries = inquiryRepository.findByTenantIdOrderByCreatedAtDesc(tenantId);
        Map<UUID, BrandContact> brands = contactRepository.findAllById(
                        inquiries.stream().map(BrandInquiry::getBrandContactId).distinct().toList()).stream()
                .collect(Collectors.toMap(BrandContact::getId, Function.identity()));
        Map<UUID, String> titles = filmTitles(inquiries);
        Map<UUID, String> publicIds = itemRepository.findAllById(inquiries.stream().map(BrandInquiry::getShowcaseItemId)
                        .filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(ShowcaseItem::getId, ShowcaseItem::getPublicId));
        return inquiries.stream().map(i -> {
            BrandContact b = brands.get(i.getBrandContactId());
            return new CreatorInquiryView(i.getId(),
                    new CreatorInquiryView.Brand(b.getContactName(), b.getCompanyName(), b.getEmail(), b.getWebsiteUrl(), b.getIndustry()),
                    publicIds.get(i.getShowcaseItemId()), titles.get(i.getShowcaseItemId()), i.getBudgetBand(), i.getTimeline(),
                    i.getMessage(), i.getStatus(), i.getAttributionToken() != null, briefUrl(i), i.getCreatedAt());
        }).toList();
    }

    /** The creator marks a request read or declines it; converting has its own action. */
    @Transactional
    public void setStatus(UUID tenantId, UUID inquiryId, BrandInquiry.Status status) {
        if (status != BrandInquiry.Status.VIEWED && status != BrandInquiry.Status.DECLINED) {
            throw new IllegalArgumentException("A request can only be marked VIEWED or DECLINED here");
        }
        BrandInquiry inquiry = owned(tenantId, inquiryId);
        if (inquiry.getStatus() == BrandInquiry.Status.CONVERTED) {
            throw new IllegalStateException("This request already became a brief");
        }
        inquiry.setStatus(status);
        inquiryRepository.save(inquiry);
    }

    /** Creates the brief in creative-planning and mails the brand its link. */
    @Transactional
    public CreatorInquiryView convert(UUID tenantId, UUID inquiryId) {
        BrandInquiry inquiry = owned(tenantId, inquiryId);
        if (inquiry.getStatus() == BrandInquiry.Status.CONVERTED) throw new IllegalStateException("This request already became a brief");
        if (inquiry.getStatus() == BrandInquiry.Status.DECLINED) throw new IllegalStateException("This request was declined");
        BrandContact brand = contactRepository.findById(inquiry.getBrandContactId()).orElseThrow();
        String brandName = firstNonBlank(brand.getCompanyName(), brand.getContactName(), brand.getEmail());
        CreativePlanningBriefClient.CreatedBrief brief = briefClient.createBrief(tenantId, briefText(brand, inquiry), brandName,
                brand.getIndustry() == null ? null : brand.getIndustry().name().toLowerCase(Locale.ROOT).replace('_', ' '));
        inquiry.setStatus(BrandInquiry.Status.CONVERTED);
        inquiry.setRequirementId(brief.requirementId());
        inquiry.setBriefShareToken(brief.shareToken());
        inquiryRepository.save(inquiry);

        CreatorPublicProfile creator = profileRepository.findById(tenantId).orElseThrow();
        String link = briefUrl(inquiry);
        mailer.send(new PlatformMailer.Mail(brand.getEmail(), creator.getDisplayName() + " sent you a brief",
                "Hi" + (brand.getContactName() == null ? "" : " " + brand.getContactName()) + ",\n\n"
                        + creator.getDisplayName() + " turned your request into a brief. Review it, add anything they should know, and "
                        + "confirm when you're ready:\n" + link + "\n",
                "<p>" + creator.getDisplayName() + " turned your request into a brief.</p><p><a href=\"" + link
                        + "\">Open the brief</a></p>"));
        return forCreator(tenantId).stream().filter(v -> v.id().equals(inquiryId)).findFirst().orElseThrow();
    }

    private void notifyCreator(CreatorPublicProfile creator, BrandContact brand, BrandInquiry inquiry) {
        Optional<String> to = tenantRepository.findById(creator.getTenantId()).map(Tenant::getPrimaryContactEmail);
        if (to.isEmpty()) return;
        String who = firstNonBlank(brand.getCompanyName(), brand.getContactName(), brand.getEmail());
        mailer.send(new PlatformMailer.Mail(to.get(), who + " asked you for a video",
                who + " sent you a request on Dalai Llama:\n\n\"" + inquiry.getMessage() + "\"\n\nSee it and reply: "
                        + brandProperties.creatorRequestsUrl() + "\n",
                null));
    }

    private Map<UUID, String> filmTitles(List<BrandInquiry> inquiries) {
        List<ShowcaseItem> items = itemRepository.findAllById(inquiries.stream().map(BrandInquiry::getShowcaseItemId)
                .filter(java.util.Objects::nonNull).distinct().toList());
        Map<String, String> videoTitles = videoRepository.findAllById(items.stream().map(ShowcaseItem::getYoutubeVideoId).toList())
                .stream().filter(v -> v.getTitle() != null).collect(Collectors.toMap(YouTubeVideo::getVideoId, YouTubeVideo::getTitle));
        return items.stream().collect(Collectors.toMap(ShowcaseItem::getId,
                i -> i.getTitleOverride() != null ? i.getTitleOverride() : videoTitles.getOrDefault(i.getYoutubeVideoId(), "")));
    }

    private String briefUrl(BrandInquiry inquiry) {
        return inquiry.getBriefShareToken() == null ? null : brandProperties.briefPageUrl() + inquiry.getBriefShareToken();
    }

    private BrandInquiry owned(UUID tenantId, UUID inquiryId) {
        return inquiryRepository.findById(inquiryId).filter(i -> i.getTenantId().equals(tenantId))
                .orElseThrow(() -> new IllegalArgumentException("No such request"));
    }

    private static String briefText(BrandContact brand, BrandInquiry inquiry) {
        StringBuilder text = new StringBuilder("Request from ")
                .append(firstNonBlank(brand.getCompanyName(), brand.getContactName(), brand.getEmail()))
                .append(" via Dalai Llama.");
        if (inquiry.getBudgetBand() != null) text.append("\nBudget: ").append(inquiry.getBudgetBand());
        if (inquiry.getTimeline() != null) text.append("\nTimeline: ").append(inquiry.getTimeline());
        return text.append("\n\n").append(inquiry.getMessage()).toString();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
