package com.dalai.llama.tenant.leadmanagement.inquiry;

import com.dalai.llama.tenant.domain.entity.Tenant;
import com.dalai.llama.tenant.leadmanagement.brand.BrandAccountService;
import com.dalai.llama.tenant.leadmanagement.brand.BrandContact;
import com.dalai.llama.tenant.leadmanagement.brand.BrandContactRepository;
import com.dalai.llama.tenant.leadmanagement.brand.BrandDtos.GuestContact;
import com.dalai.llama.tenant.leadmanagement.brand.BrandNotSignedInException;
import com.dalai.llama.tenant.leadmanagement.brand.BrandProperties;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.BrandInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.CreatorInquiryView;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmitInquiryRequest;
import com.dalai.llama.tenant.leadmanagement.inquiry.InquiryDtos.SubmittedInquiry;
import com.dalai.llama.tenant.leadmanagement.notify.PlatformMailer;
import com.dalai.llama.tenant.repository.TenantRepository;
import com.dalai.llama.tenant.showcase.client.UpstreamUnavailableException;
import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.ShowcaseIndustry;
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
import org.springframework.web.util.HtmlUtils;

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

/** "Request a video": a brand asks a creator, or Dalaillama, which routes it to one. As soon as a
 * creator holds the request it becomes a priced brief ({@link RequestPricing}) and the brand is
 * mailed the link to review, edit and pay upfront; if creative-planning is down, the creator turns
 * it into a brief by hand later. */
@Slf4j
@Service
@RequiredArgsConstructor
public class BrandInquiryService {

    /** Requests one brand can send per day, across all creators: enough for a real search, too few
     * to spam with. */
    static final int MAX_REQUESTS_PER_BRAND_PER_DAY = 10;

    private final BrandInquiryRepository inquiryRepository;
    private final InquiryImageStore images;
    private final BrandContactRepository contactRepository;
    private final BrandAccountService accounts;
    private final CreatorPublicProfileRepository profileRepository;
    private final ShowcaseItemRepository itemRepository;
    private final YouTubeVideoRepository videoRepository;
    private final TenantRepository tenantRepository;
    private final CreativePlanningBriefClient briefClient;
    private final com.dalai.llama.tenant.leadmanagement.routing.RequestRouter router;
    private final com.dalai.llama.tenant.leadmanagement.routing.RequestRoutingStore routingStore;
    private final com.dalai.llama.tenant.leadmanagement.routing.CreatorRequestNotifier notifier;
    private final ProjectReviewLinks reviewLinks;
    private final RequestPricing pricing;
    private final PlatformMailer mailer;
    private final BrandProperties brandProperties;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    @Transactional
    public SubmittedInquiry submit(UUID brandContactId, String handle, SubmitInquiryRequest request) {
        return submit(brandContactId, handle, request, java.util.List.of(), java.util.List.of());
    }

    /** With the brand's product and reference images (checked before anything is saved). */
    @Transactional
    public SubmittedInquiry submit(UUID brandContactId, String handle, SubmitInquiryRequest request,
                                   java.util.List<org.springframework.web.multipart.MultipartFile> productImages,
                                   java.util.List<org.springframework.web.multipart.MultipartFile> referenceImages) {
        BrandContact brand = checkedBrand(brandContactId, productImages, referenceImages);
        CreatorPublicProfile creator = profileRepository.findByHandle(handle)
                .filter(p -> p.getStatus() == ProfileStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown creator"));
        // Only a live film of this creator counts as "the film they were watching".
        Optional<ShowcaseItem> film = Optional.ofNullable(request.publicId())
                .flatMap(itemRepository::findByPublicId)
                .filter(i -> creator.getTenantId().equals(i.getTenantId()) && i.getStatus() == ShowcaseItemStatus.LIVE);
        BrandInquiry inquiry = create(brandContactId, creator.getTenantId(), BrandInquiry.Source.CREATOR, film, request,
                productImages, referenceImages);
        return submitted(inquiry.getId());
    }

    /**
     * "Request a video" from the landing page: sent to Dalaillama, not to a creator. The router
     * finds the creator (or leaves it for ops); the brand follows it on their page as usual.
     */
    @Transactional
    public SubmittedInquiry submitToDalaillama(UUID brandContactId, SubmitInquiryRequest request,
                                               java.util.List<org.springframework.web.multipart.MultipartFile> productImages,
                                               java.util.List<org.springframework.web.multipart.MultipartFile> referenceImages) {
        checkedBrand(brandContactId, productImages, referenceImages);
        // The film they were watching, whoever made it: a signal for that film. Only a request from a
        // creator's mail is routed to that creator.
        Optional<ShowcaseItem> film = Optional.ofNullable(request.publicId())
                .flatMap(itemRepository::findByPublicId)
                .filter(i -> i.getStatus() == ShowcaseItemStatus.LIVE);
        BrandInquiry inquiry = create(brandContactId, null, BrandInquiry.Source.LANDING, film, request, productImages, referenceImages);
        router.route(inquiry.getId(), mailingCreator(request));
        return submitted(inquiry.getId());
    }

    /**
     * The same request from someone who hasn't signed in: their email finds or makes the brand.
     * Everything after (the brief, paying, the review) reaches them by mail, so they never need to
     * sign in; until a creator has it, a receipt says what happens next.
     */
    @Transactional
    public SubmittedInquiry submitAsGuest(GuestContact guest, SubmitInquiryRequest request,
                                          java.util.List<org.springframework.web.multipart.MultipartFile> productImages,
                                          java.util.List<org.springframework.web.multipart.MultipartFile> referenceImages) {
        UUID brandContactId = accounts.guest(guest, request.industry());
        SubmittedInquiry submitted = submitToDalaillama(brandContactId, request, productImages, referenceImages);
        if (submitted.briefUrl() == null) mailer.sendAfterCommit(receivedMail(contactRepository.findById(brandContactId).orElseThrow()));
        return submitted;
    }

    private static PlatformMailer.Mail receivedMail(BrandContact brand) {
        String text = "Hi " + brand.getContactName() + ",\n\n"
                + "We've got your video request. As soon as a creator takes it, usually within a day, we'll mail you the "
                + "brief. Open it to check the details and pay upfront; production starts as soon as you do.\n\n"
                + "Reply to this mail if you want to add anything.\n";
        String html = "<p>Hi " + HtmlUtils.htmlEscape(brand.getContactName()) + ",</p>"
                + "<p>We've got your video request. As soon as a creator takes it, usually within a day, we'll mail you the "
                + "brief. Open it to check the details and pay upfront; production starts as soon as you do.</p>"
                + "<p style=\"color:#64748b\">Reply to this mail if you want to add anything.</p>";
        return new PlatformMailer.Mail(brand.getEmail(), "We've got your video request", text, html);
    }

    /**
     * The creator whose mail the request came from: the one it names, else the one whose tracked
     * link brought the brand here. Null for a plain request from the site.
     */
    private UUID mailingCreator(SubmitInquiryRequest request) {
        if (request.creator() != null && !request.creator().isBlank()) {
            Optional<UUID> named = profileRepository.findByHandle(request.creator().trim().toLowerCase(java.util.Locale.ROOT))
                    .filter(p -> p.getStatus() == ProfileStatus.ACTIVE).map(CreatorPublicProfile::getTenantId);
            if (named.isPresent()) return named.get();
        }
        if (request.attributionToken() == null) return null;
        return jdbc.query("SELECT tenant_id FROM lead_outreach_link WHERE token = ?", (rs, n) -> rs.getObject(1, UUID.class),
                request.attributionToken()).stream().findFirst().orElse(null);
    }

    /** The signed-in brand, once their images pass and they are under the daily limit. */
    private BrandContact checkedBrand(UUID brandContactId,
                                      java.util.List<org.springframework.web.multipart.MultipartFile> productImages,
                                      java.util.List<org.springframework.web.multipart.MultipartFile> referenceImages) {
        InquiryImageStore.validate(productImages);
        InquiryImageStore.validate(referenceImages);
        BrandContact brand = contactRepository.findById(brandContactId).orElseThrow(BrandNotSignedInException::new);
        Integer today = jdbc.queryForObject(
                "SELECT COUNT(*) FROM lead_brand_inquiry WHERE brand_contact_id = ? AND created_at > ?", Integer.class,
                brandContactId, Timestamp.from(OffsetDateTime.now(clock).minusDays(1).toInstant()));
        if (today != null && today >= MAX_REQUESTS_PER_BRAND_PER_DAY) {
            throw new IllegalStateException("You've sent a lot of requests today; please try again tomorrow");
        }
        return brand;
    }

    private BrandInquiry create(UUID brandContactId, UUID creatorTenantId, BrandInquiry.Source source, Optional<ShowcaseItem> film,
                                SubmitInquiryRequest request,
                                java.util.List<org.springframework.web.multipart.MultipartFile> productImages,
                                java.util.List<org.springframework.web.multipart.MultipartFile> referenceImages) {
        // Flushed now: the image rows below are written with JDBC and reference this row.
        BrandInquiry inquiry = inquiryRepository.saveAndFlush(BrandInquiry.builder()
                .id(UUID.randomUUID())
                .tenantId(creatorTenantId)
                .source(source)
                .industry(request.industry())
                .brandContactId(brandContactId)
                .showcaseItemId(film.map(ShowcaseItem::getId).orElse(null))
                .attributionToken(request.attributionToken())
                .budgetBand(request.budgetBand())
                .timeline(request.timeline())
                .message(request.message().trim())
                .productName(blankToNull(request.productName()))
                .productDescription(blankToNull(request.productDescription()))
                .targetAudience(blankToNull(request.targetAudience()))
                .campaignDirection(blankToNull(request.campaignDirection()))
                .durationSeconds(request.durationSeconds())
                .languages(request.languages() == null || request.languages().isEmpty() ? null
                        : String.join(",", request.languages().stream().map(String::trim).filter(l -> !l.isEmpty()).toList()))
                .status(BrandInquiry.Status.NEW)
                .build());
        // Brand interest is a ranking signal for the film (rule 13).
        film.ifPresent(i -> jdbc.update("UPDATE showcase_item SET inquiry_count = inquiry_count + 1 WHERE id = ?", i.getId()));
        images.save(inquiry.getId(), productImages, referenceImages);
        return inquiry;
    }

    public List<BrandInquiryView> forBrand(UUID brandContactId) {
        List<BrandInquiry> inquiries = inquiryRepository.findByBrandContactIdOrderByCreatedAtDesc(brandContactId);
        Map<UUID, CreatorPublicProfile> creators = profileRepository.findAllById(
                        inquiries.stream().map(BrandInquiry::getTenantId).filter(java.util.Objects::nonNull).distinct().toList()).stream()
                .collect(Collectors.toMap(CreatorPublicProfile::getTenantId, Function.identity()));
        Map<UUID, String> titles = filmTitles(inquiries);
        return inquiries.stream().map(i -> {
            // A request sent to Dalaillama has no creator until it is routed: "finding a creator".
            Optional<CreatorPublicProfile> p = Optional.ofNullable(i.getTenantId()).map(creators::get);
            // After it became a brief: paid yet, and the review link once the creator shared it.
            ProjectReviewLinks.Progress progress = i.getRequirementId() == null || i.getTenantId() == null
                    ? ProjectReviewLinks.Progress.UNKNOWN : reviewLinks.of(i.getTenantId(), i.getRequirementId());
            return new BrandInquiryView(i.getId(), p.map(CreatorPublicProfile::getHandle).orElse(null),
                    p.map(CreatorPublicProfile::getDisplayName).orElse(null), p.map(CreatorPublicProfile::getAvatarUrl).orElse(null),
                    titles.get(i.getShowcaseItemId()), i.getBudgetBand(), i.getTimeline(), i.getMessage(), i.getStatus(),
                    i.getStatus() == BrandInquiry.Status.EXPIRED ? null : briefUrl(i), i.getCreatedAt(), InquiryDtos.BriefDetails.of(i),
                    progress.funded(), progress.reviewUrl());
        }).toList();
    }

    public List<CreatorInquiryView> forCreator(UUID tenantId) {
        // Theirs, and requests sent to Dalaillama that are offered to them, waiting to be accepted.
        List<BrandInquiry> offered = inquiryRepository.findAllById(routingStore.offeredTo(tenantId));
        java.util.Set<UUID> offeredIds = offered.stream().map(BrandInquiry::getId).collect(Collectors.toSet());
        List<BrandInquiry> inquiries = java.util.stream.Stream.concat(offered.stream(),
                        inquiryRepository.findByTenantIdOrderByCreatedAtDesc(tenantId).stream())
                .sorted(java.util.Comparator.comparing(BrandInquiry::getCreatedAt).reversed()).toList();
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
                    i.getMessage(), i.getStatus(), i.getAttributionToken() != null, briefUrl(i), i.getCreatedAt(),
                    InquiryDtos.BriefDetails.of(i, images.refs(i.getId())), offeredIds.contains(i.getId()), i.getPaidAt() != null);
        }).toList();
    }

    /** Takes a request offered to this creator (offer to all): theirs if they are first. */
    @Transactional
    public CreatorInquiryView accept(UUID tenantId, UUID inquiryId) {
        if (!router.accept(tenantId, inquiryId)) throw new IllegalStateException("Another creator took this request first");
        briefOnceHeld(inquiryId);
        return forCreator(tenantId).stream().filter(v -> v.id().equals(inquiryId)).findFirst().orElseThrow();
    }

    /** The creator marks a request read or declines it; converting has its own action. */
    @Transactional
    public void setStatus(UUID tenantId, UUID inquiryId, BrandInquiry.Status status) {
        if (status != BrandInquiry.Status.VIEWED && status != BrandInquiry.Status.DECLINED) {
            throw new IllegalArgumentException("A request can only be marked VIEWED or DECLINED here");
        }
        BrandInquiry inquiry = visible(tenantId, inquiryId);
        if (inquiry.getStatus() == BrandInquiry.Status.CONVERTED) {
            throw new IllegalStateException("This request already became a brief");
        }
        // A request sent to Dalaillama isn't the creator's to close: passing on it sends it on to
        // another creator (or ops); merely reading an offer doesn't take it.
        if (inquiry.getSource() == BrandInquiry.Source.LANDING) {
            if (status == BrandInquiry.Status.DECLINED) {
                router.decline(tenantId, inquiryId);
                briefOnceHeld(inquiryId);
            } else if (tenantId.equals(inquiry.getTenantId())) inquiryRepository.save(withStatus(inquiry, status));
            return;
        }
        inquiry.setStatus(status);
        inquiryRepository.save(inquiry);
    }

    /** The creator turns a request into a brief by hand (when it couldn't be made automatically). */
    @Transactional
    public CreatorInquiryView convert(UUID tenantId, UUID inquiryId) {
        BrandInquiry inquiry = owned(tenantId, inquiryId);
        if (inquiry.getStatus() == BrandInquiry.Status.CONVERTED) throw new IllegalStateException("This request already became a brief");
        if (inquiry.getStatus() == BrandInquiry.Status.DECLINED) throw new IllegalStateException("This request was declined");
        makeBrief(inquiry);
        return forCreator(tenantId).stream().filter(v -> v.id().equals(inquiryId)).findFirst().orElseThrow();
    }

    /** The creator closes a request of theirs the brand never paid for. */
    @Transactional
    public CreatorInquiryView expireByCreator(UUID tenantId, UUID inquiryId) {
        expire(owned(tenantId, inquiryId));
        return forCreator(tenantId).stream().filter(v -> v.id().equals(inquiryId)).findFirst().orElseThrow();
    }

    /** Ops close a request (waiting or held) the brand never paid for. */
    @Transactional
    public void expireByOps(UUID inquiryId) {
        expire(inquiryRepository.findById(inquiryId).orElseThrow(() -> new IllegalArgumentException("No such request")));
    }

    /** Unpaid only: its brief link stops working, open offers are withdrawn, it leaves every queue. */
    private void expire(BrandInquiry inquiry) {
        if (inquiry.getStatus() == BrandInquiry.Status.EXPIRED) return;
        if (inquiry.getPaidAt() != null) throw new IllegalStateException("The brand has already paid for this request");
        if (inquiry.getStatus() == BrandInquiry.Status.DECLINED) throw new IllegalStateException("This request was declined");
        if (inquiry.getRequirementId() != null) briefClient.withdrawLink(inquiry.getTenantId(), inquiry.getRequirementId());
        routingStore.withdrawAll(inquiry.getId(), OffsetDateTime.now(clock));
        inquiry.setStatus(BrandInquiry.Status.EXPIRED);
        inquiryRepository.save(inquiry);
    }

    /** Ops hand a routed request to a creator; its brief is made at once. */
    @Transactional
    public void assignByOps(UUID inquiryId, UUID tenantId) {
        router.assignByOps(inquiryId, tenantId);
        briefOnceHeld(inquiryId);
    }

    private SubmittedInquiry submitted(UUID inquiryId) {
        Optional<String> briefUrl = briefOnceHeld(inquiryId);
        BrandInquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow();
        return new SubmittedInquiry(inquiryId, inquiry.getStatus(), briefUrl.orElse(null));
    }

    /**
     * Makes the brief if a creator holds the request and it has none yet. Best effort: when
     * creative-planning can't be reached the request stays as it is, for the creator to convert.
     */
    private Optional<String> briefOnceHeld(UUID inquiryId) {
        BrandInquiry inquiry = inquiryRepository.findById(inquiryId).orElseThrow();
        // The router claims with plain SQL, so an entity loaded earlier in this transaction can't know.
        inquiry.setTenantId(jdbc.queryForObject("SELECT tenant_id FROM lead_brand_inquiry WHERE id = ?", UUID.class, inquiryId));
        if (inquiry.getTenantId() == null || inquiry.getStatus() == BrandInquiry.Status.CONVERTED) return Optional.empty();
        try {
            return Optional.of(makeBrief(inquiry));
        } catch (UpstreamUnavailableException e) {
            log.warn("Request {} kept for its creator to convert: {}", inquiryId, e.getMessage());
            notifier.needsBrief(inquiry.getTenantId(), contactRepository.findById(inquiry.getBrandContactId()).orElseThrow(), inquiry);
            return Optional.empty();
        }
    }

    /**
     * The brand paid the brief made from a request: mark it paid and mail its creator, once. A
     * payment for any other brief, or a later one (the balance), changes nothing.
     */
    @Transactional
    public void onBriefPaid(UUID requirementId, java.math.BigDecimal amount, String currency) {
        Optional<BrandInquiry> found = inquiryRepository.findByRequirementId(requirementId).filter(i -> i.getPaidAt() == null);
        if (found.isEmpty()) return;
        BrandInquiry inquiry = found.get();
        inquiry.setPaidAt(OffsetDateTime.now(clock));
        inquiryRepository.save(inquiry);
        notifier.paid(inquiry.getTenantId(), contactRepository.findById(inquiry.getBrandContactId()).orElseThrow(), inquiry,
                amount == null ? "Your share" : BriefReadyMail.money(amount, currency));
    }

    /** Creates the priced brief in creative-planning, marks the request converted and mails the
     * brand the link; returns the link. */
    private String makeBrief(BrandInquiry inquiry) {
        BrandContact brand = contactRepository.findById(inquiry.getBrandContactId()).orElseThrow();
        String brandName = firstNonBlank(brand.getCompanyName(), brand.getContactName(), brand.getEmail());
        InquiryDtos.BriefDetails details = InquiryDtos.BriefDetails.of(inquiry);
        RequestPricing.Price price = pricing.of(inquiry);
        ShowcaseIndustry industry = inquiry.getIndustry() != null ? inquiry.getIndustry() : brand.getIndustry();
        CreativePlanningBriefClient.CreatedBrief brief = briefClient.createBrief(inquiry.getTenantId(), briefText(brand, inquiry), brandName,
                industry == null ? null : industry.name().toLowerCase(Locale.ROOT).replace('_', ' '),
                details, images.all(inquiry.getId()), new CreativePlanningBriefClient.Pricing(price.pricePerSecond(), price.prepaymentPercent()));
        inquiry.setStatus(BrandInquiry.Status.CONVERTED);
        inquiry.setRequirementId(brief.requirementId());
        inquiry.setBriefShareToken(brief.shareToken());
        inquiryRepository.save(inquiry);

        CreatorPublicProfile creator = profileRepository.findById(inquiry.getTenantId()).orElseThrow();
        String link = briefUrl(inquiry);
        mailer.sendAfterCommit(BriefReadyMail.of(brand, creator.getDisplayName(), link, brief));
        return link;
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

    /** One attached image, for the creator who received the request. */
    public Optional<InquiryImageStore.Image> image(UUID tenantId, UUID inquiryId, UUID imageId) {
        visible(tenantId, inquiryId);
        return images.one(inquiryId, imageId);
    }

    private BrandInquiry owned(UUID tenantId, UUID inquiryId) {
        return inquiryRepository.findById(inquiryId).filter(i -> tenantId.equals(i.getTenantId()))
                .orElseThrow(() -> new IllegalArgumentException("No such request"));
    }

    /** The creator's own request, or one offered to them. */
    private BrandInquiry visible(UUID tenantId, UUID inquiryId) {
        return inquiryRepository.findById(inquiryId)
                .filter(i -> tenantId.equals(i.getTenantId()) || routingStore.isOffered(inquiryId, tenantId))
                .orElseThrow(() -> new IllegalArgumentException("No such request"));
    }

    private static BrandInquiry withStatus(BrandInquiry inquiry, BrandInquiry.Status status) {
        inquiry.setStatus(status);
        return inquiry;
    }

    private static String briefText(BrandContact brand, BrandInquiry inquiry) {
        StringBuilder text = new StringBuilder("Request from ")
                .append(firstNonBlank(brand.getCompanyName(), brand.getContactName(), brand.getEmail()))
                .append(" via Dalai Llama.");
        if (inquiry.getBudgetBand() != null) text.append("\nBudget: ").append(inquiry.getBudgetBand());
        if (inquiry.getTimeline() != null) text.append("\nTimeline: ").append(inquiry.getTimeline());
        return text.append("\n\n").append(inquiry.getMessage()).toString();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v;
        return "";
    }
}
