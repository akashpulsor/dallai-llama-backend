package com.dalai.llama.creator.service;

import com.dalai.llama.creator.dto.request.GenerateProductAdPipelineRequest;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ProductAdResearchService {

    private static final Logger log = LoggerFactory.getLogger(ProductAdResearchService.class);
    private static final int MAX_HTML_CHARS = 1_800_000;
    private static final int MAX_PROMPT_CHARS = 36_000;
    private static final Pattern META_TAG_PATTERN = Pattern.compile("<meta\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern LINK_TAG_PATTERN = Pattern.compile("<link\\b[^>]*>", Pattern.CASE_INSENSITIVE);
    private static final Pattern SCRIPT_JSON_LD_PATTERN = Pattern.compile(
            "<script\\b[^>]*type\\s*=\\s*['\"]application/ld\\+json['\"][^>]*>(.*?)</script>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern TITLE_PATTERN = Pattern.compile("<title\\b[^>]*>(.*?)</title>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern HEX_COLOR_PATTERN = Pattern.compile("#[0-9a-fA-F]{6}\\b");
    private static final Pattern DDG_RESULT_PATTERN = Pattern.compile(
            "<a\\b[^>]*class\\s*=\\s*['\"][^'\"]*result__a[^'\"]*['\"][^>]*href\\s*=\\s*['\"]([^'\"]+)['\"][^>]*>(.*?)</a>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Duration FETCH_TIMEOUT = Duration.ofSeconds(12);

    private final CreatorAiService creatorAiService;
    private final CreatorCreativeLearningService creativeLearningService;
    private final ObjectMapper objectMapper;
    private final WebClient webClient;

    public ProductAdResearchService(
            CreatorAiService creatorAiService,
            CreatorCreativeLearningService creativeLearningService,
            ObjectMapper objectMapper,
            WebClient.Builder webClientBuilder
    ) {
        this.creatorAiService = creatorAiService;
        this.creativeLearningService = creativeLearningService;
        this.objectMapper = objectMapper;
        this.webClient = webClientBuilder
                .defaultHeader(HttpHeaders.USER_AGENT, "DalaiLlamaProductResearchBot/1.0 (+https://dalaillama.in)")
                .defaultHeader(HttpHeaders.ACCEPT, MediaType.TEXT_HTML_VALUE + ",application/xhtml+xml,application/json;q=0.9,*/*;q=0.8")
                .codecs(configurer -> configurer.defaultCodecs().maxInMemorySize(8 * 1024 * 1024))
                .build();
    }

    public Map<String, Object> buildProductAdPlan(
            GenerateProductAdPipelineRequest request,
            String tenantId,
            String userId,
            UUID generationJobId
    ) {
        GenerateProductAdPipelineRequest safeRequest = request == null
                ? new GenerateProductAdPipelineRequest(null, null, List.of(), null, null, null, null, null, null, null, null, null, null, null, null, null, false, true, true, true, true, null, null, null, null, Map.of(), Map.of(), null)
                : request;
        Map<String, Object> scraped = scrapeProductPage(safeRequest.productUrl());
        List<String> suppliedImages = cleanUrls(safeRequest.productImageUrls());
        List<Map<String, Object>> searchResults = Boolean.FALSE.equals(safeRequest.useWebSearch())
                ? List.of()
                : searchSnippets(searchQuery(safeRequest, scraped));
        List<Map<String, Object>> approvedCreativeLearnings = creativeLearningService.approvedGuidance(
                tenantId,
                userId,
                safeRequest.categoryCode(),
                adFormatFor(safeRequest),
                firstText(
                        mapValue(safeRequest.existingBrief()).get("productCategory"),
                        mapValue(safeRequest.brandContext()).get("productCategory"),
                        mapValue(scraped.get("product")).get("category")
                )
        );
        if (approvedCreativeLearnings == null) {
            approvedCreativeLearnings = List.of();
        }

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("renderedPrompt", buildResearchPrompt(
                safeRequest,
                scraped,
                suppliedImages,
                searchResults,
                approvedCreativeLearnings
        ));
        input.put("useGoogleSearch", !Boolean.FALSE.equals(safeRequest.useWebSearch()));
        input.put("productUrl", safeRequest.productUrl());
        input.put("productName", safeRequest.productName());
        input.put("ingredientDetails", safeRequest.ingredientDetails());
        input.put("referenceImageUrls", suppliedImages);
        input.put("productReferenceImageUrls", suppliedImages);
        input.put("attachReferenceImages", !suppliedImages.isEmpty());
        input.put("durationSeconds", durationSeconds(safeRequest.durationSeconds()));
        input.put("conceptCount", conceptCount(safeRequest.conceptCount()));
        input.put("imageCount", imageCount(safeRequest.imageCount()));
        input.put("scrapedProductPage", scraped);
        input.put("directSearchResults", searchResults);
        input.put("approvedCreativeLearnings", approvedCreativeLearnings);

        Map<String, Object> aiOutput = new LinkedHashMap<>();
        Map<String, Object> aiTokenMetadata = new LinkedHashMap<>();
        Map<String, Object> aiCostMetadata = new LinkedHashMap<>();
        String aiStatus = "SKIPPED";
        try {
            CreatorAiService.MeteredAiResponse response = creatorAiService.generateMetered(
                    "PRODUCT_AD_RESEARCH",
                    input,
                    new CreatorAiService.AiUsageContext(
                            tenantId,
                            userId,
                            safeRequest.projectId(),
                            generationJobId,
                            null
                    )
            );
            aiOutput = response.output() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.output());
            aiTokenMetadata = response.tokenMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.tokenMetadata());
            aiCostMetadata = response.costMetadata() == null ? new LinkedHashMap<>() : new LinkedHashMap<>(response.costMetadata());
            creatorAiService.publishBillingDebit(
                    "PRODUCT_AD_RESEARCH",
                    response,
                    new CreatorAiService.AiUsageContext(tenantId, userId, safeRequest.projectId(), generationJobId, null)
            );
            aiStatus = "COMPLETED";
        } catch (RuntimeException ex) {
            aiStatus = "FAILED";
            aiOutput.put("researchError", defaultString(ex.getMessage(), ex.getClass().getSimpleName()));
            log.warn(
                    "Product ad AI research failed tenantId={} userId={} productUrl={} productName={} errorType={} errorMessage={}",
                    tenantId,
                    userId,
                    safeRequest.productUrl(),
                    safeRequest.productName(),
                    ex.getClass().getSimpleName(),
                    ex.getMessage()
            );
        }

        Map<String, Object> plan = normalizePlan(safeRequest, scraped, suppliedImages, searchResults, aiOutput);
        plan.put("aiResearchStatus", aiStatus);
        plan.put("aiTokenMetadata", aiTokenMetadata);
        plan.put("aiCostMetadata", aiCostMetadata);
        plan.put("generatedAt", OffsetDateTime.now().toString());
        plan.put("approvedCreativeLearnings", approvedCreativeLearnings);
        plan.put("creativeLearningApplied", !approvedCreativeLearnings.isEmpty());
        return plan;
    }

    private Map<String, Object> normalizePlan(
            GenerateProductAdPipelineRequest request,
            Map<String, Object> scraped,
            List<String> suppliedImages,
            List<Map<String, Object>> searchResults,
            Map<String, Object> aiOutput
    ) {
        Map<String, Object> productIntelligence = new LinkedHashMap<>();
        productIntelligence.putAll(mapValue(request.existingBrief()));
        productIntelligence.putAll(mapValue(request.brandContext()));
        productIntelligence.putAll(mapValue(scraped.get("product")));
        productIntelligence.putAll(firstMap(aiOutput.get("productIntelligence"), aiOutput.get("product")));
        putIfText(productIntelligence, "productUrl", request.productUrl());
        putIfText(productIntelligence, "productName", firstText(request.productName(), productIntelligence.get("name"), scraped.get("title")));
        putIfText(productIntelligence, "ingredients", firstText(
                request.ingredientDetails(),
                productIntelligence.get("ingredients"),
                mapValue(request.existingBrief()).get("ingredientDetails")
        ));
        putIfText(productIntelligence, "targetAudience", firstText(
                request.targetAudience(),
                productIntelligence.get("targetAudience")
        ));
        putIfText(productIntelligence, "campaignObjective", request.campaignObjective());
        productIntelligence.put("sourceImages", mergedUniqueStrings(
                suppliedImages,
                stringList(scraped.get("imageUrls")),
                stringList(productIntelligence.get("imageUrls"))
        ));
        productIntelligence.put("brandColors", mergedUniqueStrings(
                stringList(productIntelligence.get("brandColors")),
                stringList(scraped.get("brandColors"))
        ));
        String adTone = firstText(
                productIntelligence.get("adTone"),
                productIntelligence.get("recommendedAdTone"),
                aiOutput.get("adTone"),
                aiOutput.get("recommendedAdTone"),
                productIntelligence.get("tone"),
                requestedTone(request),
                fallbackAdTone(request, productIntelligence)
        );
        String toneRationale = firstText(
                productIntelligence.get("toneRationale"),
                aiOutput.get("toneRationale"),
                "Selected from the product visuals, ingredients or product details, audience, and campaign objective."
        );
        productIntelligence.put("tone", adTone);
        productIntelligence.put("adTone", adTone);
        productIntelligence.put("toneRationale", toneRationale);
        productIntelligence.put("toneSelectionMode", "AUTO_FROM_PRODUCT_CONTEXT");
        productIntelligence.put("extraction", Map.of(
                "pageScrapeStatus", defaultString(scraped.get("status"), "NOT_REQUESTED"),
                "aiResearchStatus", defaultString(aiOutput.get("status"), ""),
                "generatedAt", OffsetDateTime.now().toString()
        ));

        Map<String, Object> marketResearch = firstMap(aiOutput.get("marketResearch"), aiOutput.get("research"), aiOutput.get("competitorAnalysis"));
        marketResearch.putIfAbsent("directSearchResults", searchResults);
        marketResearch.putIfAbsent("competitors", firstList(aiOutput.get("competitors")));
        marketResearch.putIfAbsent("positioning", firstText(aiOutput.get("positioning"), productIntelligence.get("positioning")));
        if (aiOutput.containsKey("groundingMetadata")) {
            marketResearch.put("groundingMetadata", aiOutput.get("groundingMetadata"));
            marketResearch.put("groundedWithGoogleSearch", aiOutput.get("groundedWithGoogleSearch"));
        }

        List<Map<String, Object>> adConcepts = firstMapList(aiOutput.get("adConcepts"), aiOutput.get("concepts"));
        if (adConcepts.isEmpty()) {
            adConcepts = fallbackConcepts(productIntelligence, request);
        }

        List<Map<String, Object>> shotPlan = firstMapList(aiOutput.get("shotPlan"), aiOutput.get("shots"), aiOutput.get("storyboard"));
        if (shotPlan.isEmpty()) {
            shotPlan = fallbackShotPlan(productIntelligence, request);
        }
        shotPlan = applyCreativeDirectionToShots(shotPlan, request, productIntelligence);
        Map<String, Object> hookPlan = fallbackHookPlan(productIntelligence, shotPlan, request);
        hookPlan.putAll(firstMap(aiOutput.get("hookPlan"), aiOutput.get("openingHookPlan")));
        Map<String, Object> retentionPlan = fallbackRetentionPlan(shotPlan, request);
        retentionPlan.putAll(firstMap(aiOutput.get("retentionPlan"), aiOutput.get("watchRetentionPlan")));
        shotPlan = applyHookAndRetentionDirectionToShots(shotPlan, hookPlan, retentionPlan);

        List<Map<String, Object>> imagePrompts = firstMapList(aiOutput.get("imagePrompts"), aiOutput.get("generatedImagePrompts"));
        if (imagePrompts.isEmpty()) {
            imagePrompts = fallbackImagePrompts(productIntelligence, shotPlan, request);
        }
        imagePrompts = applyCreativeDirectionToImagePrompts(imagePrompts, shotPlan, request, productIntelligence);
        imagePrompts = normalizeImagePromptCount(imagePrompts, request);
        shotPlan = applyGenerationPromptsToShots(shotPlan, imagePrompts, request, productIntelligence);

        Map<String, Object> videoPacingProfile = firstMap(aiOutput.get("videoPacingProfile"));
        videoPacingProfile.putIfAbsent("pacing", firstText(request.pacingStyle(), "FAST"));
        videoPacingProfile.putIfAbsent("durationSeconds", durationSeconds(request.durationSeconds()));
        videoPacingProfile.putIfAbsent("shotRhythm", "fast".equalsIgnoreCase(firstText(request.pacingStyle()))
                ? "1.5-3 second shots with quick product proof beats"
                : "3-5 second cinematic shots with more hold time");
        videoPacingProfile.putIfAbsent("hookWindow", firstText(hookPlan.get("timing"), "0-2 seconds"));
        videoPacingProfile.putIfAbsent("retentionRhythm", firstText(retentionPlan.get("rhythm"), "Introduce a new proof, visual payoff, or pattern interrupt every 2-4 seconds."));

        Map<String, Object> videoConsistencyBible = firstMap(aiOutput.get("videoConsistencyBible"));
        videoConsistencyBible.putIfAbsent("productLock", productIntelligence);
        videoConsistencyBible.putIfAbsent("visualLocks", Map.of(
                "brandColors", productIntelligence.getOrDefault("brandColors", List.of()),
                "packaging", firstText(productIntelligence.get("packaging"), productIntelligence.get("packagingDescription")),
                "logo", firstText(productIntelligence.get("logoUrl"), productIntelligence.get("logo"))
        ));
        videoConsistencyBible.putIfAbsent("seedStrategy", "Use one deterministic seriesSeed across all shots and sceneSeed = seriesSeed + shotNumber.");
        if (noHumans(request)) {
            videoConsistencyBible.put("noHumans", true);
            videoConsistencyBible.put("humanExclusionLock", noHumansNegativePrompt());
        }

        Map<String, Object> musicPlan = new LinkedHashMap<>(fallbackMusicPlan(request, productIntelligence));
        musicPlan.putAll(firstMap(aiOutput.get("musicPlan")));
        Map<String, Object> freeMusicPlan = new LinkedHashMap<>(fallbackFreeMusicPlan(request, productIntelligence));
        freeMusicPlan.putAll(firstMap(aiOutput.get("freeMusicPlan"), aiOutput.get("royaltyFreeMusicPlan")));
        Map<String, Object> soundDesignPlan = new LinkedHashMap<>(fallbackSoundDesignPlan(request));
        soundDesignPlan.putAll(firstMap(aiOutput.get("soundDesignPlan")));
        Map<String, Object> dialoguePlan = firstMap(aiOutput.get("dialoguePlan"), aiOutput.get("voicePlan"));
        if (dialoguePlan.isEmpty()) {
            dialoguePlan = fallbackDialoguePlan(productIntelligence, shotPlan, request);
        }
        List<Map<String, Object>> srtCues = firstMapList(aiOutput.get("srtCues"), mapValue(aiOutput.get("srtFile")).get("cues"));
        if (srtCues.isEmpty()) {
            srtCues = fallbackSrtCues(shotPlan, dialoguePlan, request);
        }

        Map<String, Object> videoFinishingPlan = firstMap(aiOutput.get("videoFinishingPlan"));
        videoFinishingPlan.putIfAbsent("imageLedAdMode", true);
        videoFinishingPlan.putIfAbsent("referenceImageMode", "product_motion_anchor");
        videoFinishingPlan.putIfAbsent("requireImageAnchors", true);
        videoFinishingPlan.putIfAbsent("voiceDialoguePrompt", firstText(dialoguePlan.get("voiceoverScript"), dialoguePlan.get("script")));
        videoFinishingPlan.putIfAbsent("backgroundMusicPrompt", firstText(musicPlan.get("prompt")));
        videoFinishingPlan.putIfAbsent("ambiencePrompt", "Scene-matched low room tone or product environment ambience.");
        videoFinishingPlan.putIfAbsent("soundFxPrompt", "Small whooshes, clicks, transitions, pours, fizz, texture hits only where they support the edit.");
        videoFinishingPlan.putIfAbsent("audioMixStandards", audioMixStandards());
        videoFinishingPlan.putIfAbsent("hookPlan", hookPlan);
        videoFinishingPlan.putIfAbsent("retentionPlan", retentionPlan);
        videoFinishingPlan.putIfAbsent("editingPlan", firstMap(aiOutput.get("editingPlan")));

        Map<String, Object> editorHandoffPlan = editorHandoffPlan(
                request,
                productIntelligence,
                marketResearch,
                adConcepts,
                shotPlan,
                imagePrompts,
                dialoguePlan,
                musicPlan,
                freeMusicPlan,
                soundDesignPlan,
                videoFinishingPlan,
                videoPacingProfile,
                videoConsistencyBible,
                srtCues,
                aiOutput
        );
        videoFinishingPlan.put("editingPlan", editorHandoffPlan);
        videoFinishingPlan.put("editorHandoffPlan", editorHandoffPlan);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("productIntelligence", productIntelligence);
        result.put("adTone", adTone);
        result.put("toneRationale", toneRationale);
        result.put("creativeBrief", creativeBrief(request));
        result.put("adFormat", adFormatFor(request));
        result.put("selectedShotTypes", shotTypeRecipe(request, productIntelligence));
        result.put("noHumans", noHumans(request));
        result.put("shotPlanningMode", autoPlanShotTypes(request) ? "AUTO_PRODUCT_AWARE" : "CUSTOM");
        result.put("marketResearch", marketResearch);
        result.put("adConcepts", adConcepts);
        result.put("hookPlan", hookPlan);
        result.put("retentionPlan", retentionPlan);
        result.put("shotPlan", shotPlan);
        result.put("imagePrompts", imagePrompts);
        result.put("dialoguePlan", dialoguePlan);
        result.put("musicPlan", musicPlan);
        result.put("freeMusicPlan", freeMusicPlan);
        result.put("soundDesignPlan", soundDesignPlan);
        result.put("videoFinishingPlan", videoFinishingPlan);
        result.put("editingPlan", editorHandoffPlan);
        result.put("editorHandoffPlan", editorHandoffPlan);
        result.put("videoPacingProfile", videoPacingProfile);
        result.put("videoConsistencyBible", videoConsistencyBible);
        result.put("srtCues", srtCues);
        result.put("srt", srtText(srtCues));
        result.put("srtFile", Map.of("format", "srt", "content", srtText(srtCues), "cues", srtCues));
        result.put("rawScrape", scraped);
        result.put("rawAiOutput", compactMap(aiOutput, 20));
        return result;
    }

    private String buildResearchPrompt(
            GenerateProductAdPipelineRequest request,
            Map<String, Object> scraped,
            List<String> suppliedImages,
            List<Map<String, Object>> searchResults,
            List<Map<String, Object>> approvedCreativeLearnings
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("productUrl", request.productUrl());
        payload.put("productName", request.productName());
        payload.put("suppliedImageUrls", suppliedImages);
        payload.put("campaignObjective", request.campaignObjective());
        payload.put("targetAudience", request.targetAudience());
        payload.put("ingredientDetails", request.ingredientDetails());
        payload.put("tone", requestedTone(request));
        payload.put("toneSelectionMode", "AUTO_FROM_PRODUCT_CONTEXT");
        payload.put("categoryCode", request.categoryCode());
        payload.put("platformCode", request.platformCode());
        payload.put("durationSeconds", durationSeconds(request.durationSeconds()));
        payload.put("conceptCount", conceptCount(request.conceptCount()));
        payload.put("imageCount", imageCount(request.imageCount()));
        payload.put("screenType", firstText(request.screenType(), "vertical"));
        payload.put("pacingStyle", firstText(request.pacingStyle(), "FAST"));
        payload.put("creativeBrief", creativeBrief(request));
        payload.put("adFormat", adFormatFor(request));
        payload.put("selectedShotTypes", selectedShotTypesFor(request));
        payload.put("noHumans", noHumans(request));
        payload.put("autoPlanShotTypes", autoPlanShotTypes(request));
        payload.put("existingBrief", request.existingBrief());
        payload.put("brandContext", request.brandContext());
        payload.put("scrapedProductPage", scraped);
        payload.put("directSearchResults", searchResults);
        payload.put("approvedCreativeLearnings", approvedCreativeLearnings);

        String inputJson = writeJson(payload);
        if (inputJson.length() > MAX_PROMPT_CHARS) {
            inputJson = inputJson.substring(0, MAX_PROMPT_CHARS);
        }
        return """
                You are DalaiLlama's autonomous creative strategist for direct-response and brand video ads.
                Use the product URL/name/images and web/search evidence below to produce a complete product-ad plan.

                Rules:
                - Return only a valid JSON object.
                - Do not invent factual claims. If evidence is missing, mark fields as "unknown" or "inferred".
                - Inspect every attached product image as visual evidence. Reconcile front, back, side, label, ingredient, and packaging views into one canonical product identity.
                - Extract or infer product images, logo, brand colors, packaging, category, ingredients/materials, USP, benefits, audience, price, reviews, competitors, and positioning.
                - Treat targetAudience and campaignObjective as binding creative requirements, not passive metadata. They must shape the hook, concept, proof sequence, vocabulary, visual emphasis, retention plan, CTA, image prompts, video prompts, and sound direction. If campaignObjective is blank, infer the most commercially appropriate objective from the supplied product and audience.
                - Select the ad tone yourself from the attached product visuals, ingredientDetails, targetAudience, and campaignObjective. Store it as productIntelligence.adTone with a concise productIntelligence.toneRationale. Do not default to a generic tone when visual evidence supports a more specific direction.
                - Honor creativeBrief.adFormat as the requested primary commercial structure. Produce exactly 3 concepts, with the requested format first and two genuinely distinct alternatives.
                - Treat creativeBrief.formatPlaybook as a production contract: follow its narrative structure, hook style, retention style, music behavior, and recommended shot types. Do not turn every format into a generic product montage.
                - Apply approvedCreativeLearnings when their category, product type, and ad format match this campaign. They are client-approved creative principles and should improve story progression, shot variety, continuity, camera, lighting, pacing, and reveal discipline. They are not product facts and must never override current evidence, product identity, or explicit client instructions.
                - Create a hookPlan for the first 0-2 seconds. The opening must stop the scroll through a specific product tension, visual surprise, result, or contrast; do not open with a generic logo reveal.
                - Create a retentionPlan with a new visual proof, pattern interrupt, comparison, transformation, or payoff every 2-4 seconds. The final beat must hold a readable Pack Shot and CTA.
                - Produce a shot plan for a 60-second vertical commercial unless durationSeconds says otherwise.
                - Honor creativeBrief.requestedShotTypes. Use the selected visual shot types where they make commercial sense, include a Hero Shot and a Pack Shot, and label every shot with shotType and adFormat.
                - When autoPlanShotTypes is true, choose the shot recipe from the product category, physical behavior, packaging, ingredients/materials, and ad format. Do not fall back to a generic montage.
                - When noHumans is true, it is a hard visual constraint: no people, faces, hands, arms, bodies, human silhouettes, human reflections, presenters, or human-operated product use. Use product-only CGI, ingredients, environments, typography, and off-screen voiceover. Every shot and image prompt must carry noHumans=true and a matching negativePrompt.
                - Produce 8-10 image prompts suitable for Imagen/GPT Image/Flux/Gemini image models. Each prompt must be a product-image anchor that can later be animated into video.
                - Treat supplied product photos as product/package reference only. Keep packaging, logo, colors, and physical details stable; do not use storyboard sketches as final-product source imagery.
                - Include image-to-video motion instructions per shot: lens, camera move, lighting, physical motion, and product continuity.
                - Include dialogue or voiceover suited to the format, music, royalty-free/free music search guidance, SRT cues, and sound design. Luxury/product-showcase ads may use sparse voiceover and supers; UGC/testimonial ads should use natural spoken lines.
                - Audio must follow: consistent dialogue level, background music ducked under speech, ambient room tone, sparse small SFX, scene-matched reverb, smooth fades.
                - Include an editorHandoffPlan with exact tools to use, source assets required, per-shot edit checklist, music-license requirements, quality checklist, and deliverables for the editor queue.

                Required top-level JSON keys:
                productIntelligence (including adTone and toneRationale), marketResearch, adConcepts, shotPlan, imagePrompts,
                dialoguePlan, musicPlan, freeMusicPlan, soundDesignPlan, videoFinishingPlan,
                videoPacingProfile, videoConsistencyBible, hookPlan, retentionPlan, srtCues, editorHandoffPlan.

                Input:
                %s
                """.formatted(inputJson).trim();
    }

    private Map<String, Object> scrapeProductPage(String productUrl) {
        if (productUrl == null || productUrl.isBlank()) {
            return Map.of("status", "NOT_REQUESTED");
        }
        URI uri;
        try {
            uri = URI.create(productUrl.trim());
            if (uri.getScheme() == null || (!"http".equalsIgnoreCase(uri.getScheme()) && !"https".equalsIgnoreCase(uri.getScheme()))) {
                return Map.of("status", "INVALID_URL", "url", productUrl);
            }
        } catch (RuntimeException ex) {
            return Map.of("status", "INVALID_URL", "url", productUrl, "error", ex.getMessage());
        }

        try {
            String html = webClient
                    .get()
                    .uri(uri)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(FETCH_TIMEOUT);
            if (html == null || html.isBlank()) {
                return Map.of("status", "EMPTY_RESPONSE", "url", uri.toString());
            }
            if (html.length() > MAX_HTML_CHARS) {
                html = html.substring(0, MAX_HTML_CHARS);
            }
            return parseProductHtml(uri, html);
        } catch (RuntimeException ex) {
            return Map.of(
                    "status", "FETCH_FAILED",
                    "url", uri.toString(),
                    "host", defaultString(uri.getHost(), ""),
                    "errorType", ex.getClass().getSimpleName(),
                    "error", defaultString(ex.getMessage(), "")
            );
        }
    }

    private Map<String, Object> parseProductHtml(URI uri, String html) {
        Map<String, String> meta = metaTags(html);
        List<Map<String, Object>> jsonLdProducts = jsonLdProducts(html);
        Map<String, Object> jsonProduct = jsonLdProducts.isEmpty() ? new LinkedHashMap<>() : jsonLdProducts.get(0);

        List<String> images = mergedUniqueStrings(
                List.of(meta.get("og:image"), meta.get("twitter:image"), meta.get("image")),
                stringList(jsonProduct.get("image"))
        ).stream().map(url -> resolveUrl(uri, url)).filter(value -> !value.isBlank()).toList();

        String logo = firstText(
                stringValue(jsonProduct.get("logo")),
                resolveUrl(uri, iconLink(html)),
                meta.get("og:logo")
        );
        Map<String, Object> product = new LinkedHashMap<>();
        putIfText(product, "name", firstText(stringValue(jsonProduct.get("name")), meta.get("og:title"), meta.get("twitter:title"), title(html)));
        putIfText(product, "description", firstText(stringValue(jsonProduct.get("description")), meta.get("description"), meta.get("og:description"), meta.get("twitter:description")));
        putIfText(product, "brand", firstText(brandName(jsonProduct.get("brand")), meta.get("og:site_name"), hostBrand(uri)));
        putIfText(product, "logoUrl", logo);
        putIfText(product, "price", firstText(priceFromJsonLd(jsonProduct), meta.get("product:price:amount"), meta.get("price")));
        putIfText(product, "currency", firstText(currencyFromJsonLd(jsonProduct), meta.get("product:price:currency"), meta.get("price:currency")));
        putIfText(product, "category", firstText(stringValue(jsonProduct.get("category")), meta.get("product:category")));
        putIfText(product, "availability", firstText(offerValue(jsonProduct, "availability"), meta.get("product:availability")));
        putIfText(product, "rating", ratingValue(jsonProduct));
        putIfText(product, "reviewSummary", reviewSummary(jsonProduct));
        putIfText(product, "ingredients", ingredientsSnippet(html));
        product.put("imageUrls", images);
        product.put("brandColors", brandColors(html, meta));
        product.put("packagingDescription", "Use extracted product images and packaging visible in page metadata as the reference. If no image is available, treat packaging as unknown.");

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "SCRAPED");
        result.put("url", uri.toString());
        result.put("host", defaultString(uri.getHost(), ""));
        result.put("title", product.get("name"));
        result.put("product", product);
        result.put("imageUrls", images);
        result.put("logoUrl", logo);
        result.put("brandColors", product.get("brandColors"));
        result.put("jsonLdProductCount", jsonLdProducts.size());
        result.put("metaKeys", meta.keySet().stream().limit(40).toList());
        return result;
    }

    private List<Map<String, Object>> searchSnippets(String query) {
        if (query == null || query.isBlank()) {
            return List.of();
        }
        try {
            String encoded = URLEncoder.encode(query, StandardCharsets.UTF_8);
            String html = webClient
                    .get()
                    .uri("https://duckduckgo.com/html/?q=" + encoded)
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));
            if (html == null || html.isBlank()) {
                return List.of();
            }
            Matcher matcher = DDG_RESULT_PATTERN.matcher(html);
            List<Map<String, Object>> results = new ArrayList<>();
            int rank = 1;
            while (matcher.find() && results.size() < 6) {
                String link = decodeSearchUrl(decodeHtml(matcher.group(1)));
                String title = stripTags(matcher.group(2));
                if (title.isBlank() || link.isBlank()) {
                    continue;
                }
                results.add(Map.of(
                        "rank", rank++,
                        "title", title,
                        "url", link,
                        "source", "duckduckgo_html"
                ));
            }
            return results;
        } catch (RuntimeException ex) {
            log.debug("Product ad direct search failed query={} errorType={} errorMessage={}", query, ex.getClass().getSimpleName(), ex.getMessage());
            return List.of(Map.of(
                    "status", "SEARCH_FAILED",
                    "query", query,
                    "errorType", ex.getClass().getSimpleName(),
                    "message", defaultString(ex.getMessage(), "")
            ));
        }
    }

    private String searchQuery(GenerateProductAdPipelineRequest request, Map<String, Object> scraped) {
        String productName = firstText(
                request.productName(),
                mapValue(scraped.get("product")).get("name"),
                scraped.get("title")
        );
        String host = defaultString(scraped.get("host"), "");
        String base = firstText(productName, request.productUrl(), host);
        if (base.isBlank()) {
            return "";
        }
        return base + " product reviews competitors price benefits";
    }

    private Map<String, String> metaTags(String html) {
        Map<String, String> tags = new LinkedHashMap<>();
        Matcher matcher = META_TAG_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String tag = matcher.group();
            String key = firstText(attr(tag, "property"), attr(tag, "name"), attr(tag, "itemprop")).toLowerCase(Locale.ROOT);
            String content = attr(tag, "content");
            if (!key.isBlank() && !content.isBlank()) {
                tags.putIfAbsent(key, decodeHtml(content));
            }
        }
        return tags;
    }

    private String attr(String tag, String name) {
        if (tag == null || name == null) {
            return "";
        }
        Pattern quoted = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*(['\"])(.*?)\\1", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = quoted.matcher(tag);
        if (matcher.find()) {
            return decodeHtml(matcher.group(2).trim());
        }
        Pattern unquoted = Pattern.compile("\\b" + Pattern.quote(name) + "\\s*=\\s*([^\\s>]+)", Pattern.CASE_INSENSITIVE);
        matcher = unquoted.matcher(tag);
        return matcher.find() ? decodeHtml(matcher.group(1).trim()) : "";
    }

    private String title(String html) {
        Matcher matcher = TITLE_PATTERN.matcher(html == null ? "" : html);
        return matcher.find() ? stripTags(matcher.group(1)) : "";
    }

    private String iconLink(String html) {
        Matcher matcher = LINK_TAG_PATTERN.matcher(html == null ? "" : html);
        String fallback = "";
        while (matcher.find()) {
            String tag = matcher.group();
            String rel = attr(tag, "rel").toLowerCase(Locale.ROOT);
            String href = attr(tag, "href");
            if (href.isBlank()) {
                continue;
            }
            if (rel.contains("apple-touch-icon")) {
                return href;
            }
            if (fallback.isBlank() && rel.contains("icon")) {
                fallback = href;
            }
        }
        return fallback;
    }

    private List<Map<String, Object>> jsonLdProducts(String html) {
        List<Map<String, Object>> products = new ArrayList<>();
        Matcher matcher = SCRIPT_JSON_LD_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find()) {
            String json = decodeHtml(matcher.group(1)).trim();
            if (json.isBlank()) {
                continue;
            }
            try {
                Object parsed = objectMapper.readValue(json, Object.class);
                collectJsonLdProducts(parsed, products);
            } catch (Exception ignored) {
                // Some commerce sites include invalid tracking JSON-LD. Ignore and continue.
            }
        }
        return products;
    }

    private void collectJsonLdProducts(Object value, List<Map<String, Object>> products) {
        if (value instanceof List<?> list) {
            list.forEach(item -> collectJsonLdProducts(item, products));
            return;
        }
        Map<String, Object> map = mapValue(value);
        if (map.isEmpty()) {
            return;
        }
        Object graph = map.get("@graph");
        if (graph != null) {
            collectJsonLdProducts(graph, products);
        }
        String type = defaultString(map.get("@type"), "").toLowerCase(Locale.ROOT);
        if (type.contains("product")) {
            products.add(map);
        }
    }

    private String brandName(Object value) {
        if (value instanceof String string) {
            return string;
        }
        Map<String, Object> map = mapValue(value);
        return firstText(map.get("name"), map.get("@id"), map.get("url"));
    }

    private String priceFromJsonLd(Map<String, Object> product) {
        return firstText(offerValue(product, "price"), product.get("price"));
    }

    private String currencyFromJsonLd(Map<String, Object> product) {
        return firstText(offerValue(product, "priceCurrency"), product.get("priceCurrency"));
    }

    private String offerValue(Map<String, Object> product, String key) {
        Object offers = product == null ? null : product.get("offers");
        if (offers instanceof List<?> list) {
            for (Object item : list) {
                String value = stringValue(mapValue(item).get(key));
                if (!value.isBlank()) {
                    return value;
                }
            }
        }
        return stringValue(mapValue(offers).get(key));
    }

    private String ratingValue(Map<String, Object> product) {
        Map<String, Object> aggregateRating = mapValue(product == null ? null : product.get("aggregateRating"));
        return firstText(aggregateRating.get("ratingValue"), aggregateRating.get("ratingCount"));
    }

    private String reviewSummary(Map<String, Object> product) {
        Object reviews = product == null ? null : product.get("review");
        if (reviews instanceof List<?> list && !list.isEmpty()) {
            List<String> summaries = new ArrayList<>();
            for (Object item : list.stream().limit(3).toList()) {
                Map<String, Object> review = mapValue(item);
                String text = firstText(review.get("reviewBody"), review.get("name"));
                if (!text.isBlank()) {
                    summaries.add(truncate(text, 220));
                }
            }
            return String.join(" | ", summaries);
        }
        Map<String, Object> review = mapValue(reviews);
        return firstText(review.get("reviewBody"), review.get("name"));
    }

    private String ingredientsSnippet(String html) {
        String text = stripTags(html == null ? "" : html).replaceAll("\\s+", " ");
        String lower = text.toLowerCase(Locale.ROOT);
        int index = lower.indexOf("ingredients");
        if (index < 0) {
            index = lower.indexOf("materials");
        }
        if (index < 0) {
            return "";
        }
        int end = Math.min(text.length(), index + 700);
        return truncate(text.substring(index, end).trim(), 650);
    }

    private List<String> brandColors(String html, Map<String, String> meta) {
        Set<String> colors = new LinkedHashSet<>();
        String theme = meta.get("theme-color");
        if (theme != null && !theme.isBlank()) {
            colors.add(theme.trim());
        }
        Matcher matcher = HEX_COLOR_PATTERN.matcher(html == null ? "" : html);
        while (matcher.find() && colors.size() < 8) {
            String color = matcher.group().toUpperCase(Locale.ROOT);
            if (!List.of("#FFFFFF", "#000000").contains(color)) {
                colors.add(color);
            }
        }
        return new ArrayList<>(colors);
    }

    private Map<String, Object> creativeBrief(GenerateProductAdPipelineRequest request) {
        Map<String, Object> existing = mapValue(request.existingBrief());
        Map<String, Object> direction = mapValue(existing.get("creativeDirection"));
        Map<String, Object> brand = mapValue(request.brandContext());
        String adFormatKey = adFormatKeyFor(request);
        Map<String, Object> formatPlaybook = formatPlaybookFor(adFormatKey);
        Map<String, Object> brief = new LinkedHashMap<>();
        brief.put("adFormat", adFormatFor(request));
        brief.put("adFormatKey", adFormatKey);
        brief.put("formatPlaybook", formatPlaybook);
        brief.put("requestedShotTypes", selectedShotTypesFor(request));
        brief.put("noHumans", noHumans(request));
        brief.put("shotPlanningMode", autoPlanShotTypes(request) ? "AUTO_PRODUCT_AWARE" : "CUSTOM");
        brief.put("ingredientDetails", firstText(
                request.ingredientDetails(),
                existing.get("ingredientDetails"),
                mapValue(existing.get("productUnderstanding")).get("ingredients")
        ));
        brief.put("targetAudience", firstText(request.targetAudience(), existing.get("targetAudience")));
        brief.put("campaignObjective", firstText(request.campaignObjective(), existing.get("campaignObjective")));
        brief.put("toneSelectionMode", "AUTO_FROM_PRODUCT_CONTEXT");
        brief.put("adTone", requestedTone(request));
        brief.put("campaignNotes", firstText(
                direction.get("campaignNotes"),
                existing.get("campaignNotes"),
                existing.get("notes"),
                brand.get("offer")
        ));
        brief.put("storytellingType", firstText(direction.get("storytellingType"), existing.get("storytellingType"), brand.get("storytellingType")));
        brief.put("hookLens", firstText(direction.get("hookLens"), existing.get("hookLens"), brand.get("hookLens")));
        brief.put("hookBridge", firstText(direction.get("hookBridge"), existing.get("hookBridge")));
        brief.put("retentionStrategy", firstText(
                direction.get("retentionStrategy"),
                existing.get("retentionStrategy"),
                "Open with a product-relevant hook, refresh visual interest every few seconds, and end on a clear packshot plus CTA."
        ));
        brief.put("pacingStyle", firstText(direction.get("pacingStyle"), existing.get("pacingStyle"), request.pacingStyle(), "FAST"));
        brief.put("callToAction", firstText(existing.get("cta"), brand.get("cta"), fallbackCta(request)));
        brief.put("productReferencePolicy", firstText(
                direction.get("productReferencePolicy"),
                "Use supplied product photos only as product/package reference. Do not use storyboard sketches as final-product source imagery."
        ));
        return brief;
    }

    private String requestedTone(GenerateProductAdPipelineRequest request) {
        String tone = firstText(request == null ? null : request.tone());
        if (tone.equalsIgnoreCase("auto")
                || tone.equalsIgnoreCase("ai decides")
                || tone.equalsIgnoreCase("auto_from_product_context")) {
            return "";
        }
        return tone;
    }

    private String fallbackAdTone(
            GenerateProductAdPipelineRequest request,
            Map<String, Object> productIntelligence
    ) {
        String evidence = String.join(" ", List.of(
                firstText(request == null ? null : request.productName()),
                firstText(request == null ? null : request.ingredientDetails()),
                firstText(request == null ? null : request.targetAudience()),
                firstText(request == null ? null : request.campaignObjective()),
                firstText(productIntelligence == null ? null : productIntelligence.get("category")),
                firstText(productIntelligence == null ? null : productIntelligence.get("packaging")),
                firstText(productIntelligence == null ? null : productIntelligence.get("ingredients"))
        )).toLowerCase(Locale.ROOT);
        if (evidence.matches(".*(premium|luxury|artisan|rich|dark chocolate|gold|gourmet).*")) {
            return "premium, sensory, and assured";
        }
        if (evidence.matches(".*(health|protein|natural|clean|wellness|nutrition|fitness).*")) {
            return "credible, energetic, and ingredient-forward";
        }
        if (evidence.matches(".*(young|gen z|fun|snack|playful|social|viral).*")) {
            return "bold, playful, and social-first";
        }
        return "clear, desirable, and commercially confident";
    }

    private String adFormatFor(GenerateProductAdPipelineRequest request) {
        Map<String, Object> existing = mapValue(request.existingBrief());
        Map<String, Object> direction = mapValue(existing.get("creativeDirection"));
        Map<String, Object> brand = mapValue(request.brandContext());
        return firstText(
                direction.get("adFormat"),
                existing.get("adFormatLabel"),
                existing.get("adFormat"),
                brand.get("adFormat"),
                "Product Showcase"
        );
    }

    private String adFormatKeyFor(GenerateProductAdPipelineRequest request) {
        Map<String, Object> existing = mapValue(request.existingBrief());
        Map<String, Object> direction = mapValue(existing.get("creativeDirection"));
        Map<String, Object> brand = mapValue(request.brandContext());
        String value = firstText(
                direction.get("adFormatKey"),
                existing.get("adFormat"),
                brand.get("adFormatKey"),
                brand.get("adFormat"),
                adFormatFor(request)
        ).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return switch (value) {
            case "product_showcase", "productshowcase" -> "product_showcase";
            case "problem_solution", "problem_to_solution", "problemsolution" -> "problem_solution";
            case "lifestyle" -> "lifestyle";
            case "storytelling" -> "storytelling";
            case "demonstration", "demo" -> "demonstration";
            case "feature_highlight", "featurehighlight" -> "feature_highlight";
            case "comparison" -> "comparison";
            case "explainer" -> "explainer";
            case "testimonial_ugc", "testimonial", "ugc", "testimonial_ugc_style" -> "testimonial_ugc";
            case "emotional_brand_film", "emotional", "brand_film" -> "emotional_brand_film";
            case "promotional_offer", "promotion", "offer" -> "promotional_offer";
            case "luxury_cinematic", "luxury" -> "luxury_cinematic";
            case "motion_graphics", "motiongraphic", "motiongraphics" -> "motion_graphics";
            case "documentary_bts", "documentary", "behind_the_scenes", "bts" -> "documentary_bts";
            case "announcement_launch", "announcement", "launch" -> "announcement_launch";
            default -> "product_showcase";
        };
    }

    private Map<String, Object> formatPlaybookFor(String adFormatKey) {
        return switch (defaultString(adFormatKey, "product_showcase")) {
            case "problem_solution" -> Map.of("structure", "Problem -> product switch -> proof -> result -> CTA", "hookStyle", "Show the friction before the answer.", "retentionStyle", "Alternate before/after contrast with proof.", "musicStyle", "Tension resolves into a brighter product reveal.", "shotTypes", List.of("Action Shot", "Hero Shot", "Cutaway Shot", "Macro Shot", "Pack Shot"));
            case "lifestyle" -> Map.of("structure", "Real-life moment -> natural use -> benefit -> routine payoff -> CTA", "hookStyle", "Open inside a recognisable customer moment.", "retentionStyle", "Change the context while product use stays believable.", "musicStyle", "Warm, natural, lightly rhythmic.", "shotTypes", List.of("Lifestyle Shot", "Action Shot", "Hero Shot", "Beauty Shot", "Pack Shot"));
            case "storytelling" -> Map.of("structure", "Character moment -> tension -> product choice -> payoff -> CTA", "hookStyle", "Start mid-moment with a human question or choice.", "retentionStyle", "Advance the story with a new reveal each beat.", "musicStyle", "A small emotional arc into the payoff.", "shotTypes", List.of("Lifestyle Shot", "Action Shot", "Macro Shot", "Beauty Shot", "Pack Shot"));
            case "demonstration" -> Map.of("structure", "Claim -> show it working -> mechanism -> proof -> CTA", "hookStyle", "Lead with the result that needs proving.", "retentionStyle", "Answer the next practical question in each beat.", "musicStyle", "Precise demo pulse with room for explanation.", "shotTypes", List.of("Hero Shot", "Action Shot", "Assembly Shot", "Cutaway Shot", "Pack Shot"));
            case "feature_highlight" -> Map.of("structure", "Feature promise -> detail -> mechanism -> benefit -> CTA", "hookStyle", "Show the differentiated feature immediately.", "retentionStyle", "Reveal one feature layer at a time.", "musicStyle", "Clean premium tech-commercial rhythm.", "shotTypes", List.of("Hero Shot", "Macro Shot", "Cutaway Shot", "Floating Shot", "Pack Shot"));
            case "comparison" -> Map.of("structure", "Old way -> better product -> side-by-side proof -> result -> CTA", "hookStyle", "Make the old choice visibly less useful first.", "retentionStyle", "Keep the viewer evaluating contrast and evidence.", "musicStyle", "Tension-to-resolution comparison cue.", "shotTypes", List.of("Action Shot", "Hero Shot", "Cutaway Shot", "Macro Shot", "Pack Shot"));
            case "explainer" -> Map.of("structure", "Question -> visual explanation -> mechanism -> benefit -> CTA", "hookStyle", "Make one confusing thing simple at once.", "retentionStyle", "Answer one question per visual beat.", "musicStyle", "Clear, unobtrusive explanatory bed.", "shotTypes", List.of("Hero Shot", "Assembly Shot", "Cutaway Shot", "Floating Shot", "Pack Shot"));
            case "testimonial_ugc" -> Map.of("structure", "Discovery -> real use -> proof -> recommendation -> CTA", "hookStyle", "Start with a believable first-person result.", "retentionStyle", "Use natural reactions and small proof details.", "musicStyle", "Light social rhythm under a conversational voice.", "shotTypes", List.of("Lifestyle Shot", "Action Shot", "Hero Shot", "Beauty Shot", "Pack Shot"));
            case "emotional_brand_film" -> Map.of("structure", "Feeling -> sensory world -> meaning -> payoff -> CTA", "hookStyle", "Lead with feeling or aspiration.", "retentionStyle", "Build sensory detail and emotional progression.", "musicStyle", "Cinematic, spacious, and measured.", "shotTypes", List.of("Lifestyle Shot", "Beauty Shot", "Macro Shot", "Slow Motion Shot", "Pack Shot"));
            case "promotional_offer" -> Map.of("structure", "Offer hook -> proof -> urgency -> lockup -> CTA", "hookStyle", "Lead with the timely benefit or offer.", "retentionStyle", "Refresh the offer with proof and visual energy.", "musicStyle", "Upbeat commercial energy with a firm CTA ending.", "shotTypes", List.of("Hero Shot", "Action Shot", "Splash Shot", "Beauty Shot", "Pack Shot"));
            case "luxury_cinematic" -> Map.of("structure", "Brand-world mystery -> artifact-like partial reveal -> texture and craft -> ingredients -> earned hero packshot -> CTA", "hookStyle", "Use the first 2-3 seconds for desire and mystery; show no ingredients or complete pack.", "retentionStyle", "Use 10-20% partial reveals, macro focus, moving light, and one restrained visual payoff per beat.", "musicStyle", "Spacious premium score with restrained percussion and silence around reveal moments.", "shotTypes", List.of("Beauty Shot", "Macro Shot", "Texture Shot", "Ingredient Shot", "Pack Shot"), "captureStandard", "4K master minimum, professional cinema body and glass, 10/12-bit log or RAW intent, controlled frame rate, shutter, exposure and focus; never rookie or casual capture.", "lightingStandard", "DP and gaffer-ready motivated key, shaped fill or negative fill, rim separation, modifiers, color temperature, contrast ratio, reflection control and continuity.", "directionStandard", "Commercial director intent, product choreography, reveal discipline and motivated transitions per shot and per second.");
            case "motion_graphics" -> Map.of("structure", "Animated claim -> graphic breakdown -> benefit sequence -> CTA lockup", "hookStyle", "Open with a bold animated claim or transformation.", "retentionStyle", "Use kinetic type, callouts, and transitions to reveal one idea per beat.", "musicStyle", "Rhythmic motion-design bed with on-beat cuts.", "shotTypes", List.of("Hero Shot", "Floating Shot", "Assembly Shot", "Cutaway Shot", "Pack Shot"));
            case "documentary_bts" -> Map.of("structure", "Process -> craft proof -> close detail -> finished product -> CTA", "hookStyle", "Open on a real maker action or material truth.", "retentionStyle", "Reveal the next part of the process while building trust.", "musicStyle", "Grounded tactile score with subtle natural ambience.", "shotTypes", List.of("Action Shot", "Ingredient Shot", "Macro Shot", "Texture Shot", "Pack Shot"));
            case "announcement_launch" -> Map.of("structure", "What is new -> reveal -> why it matters -> launch energy -> CTA", "hookStyle", "Make the new thing and its relevance visible immediately.", "retentionStyle", "Escalate reveal, proof, and launch momentum.", "musicStyle", "Forward launch cue with a reveal hit.", "shotTypes", List.of("Hero Shot", "Floating Shot", "Beauty Shot", "Action Shot", "Pack Shot"));
            default -> Map.of("structure", "Product reveal -> sensory proof -> key detail -> packshot CTA", "hookStyle", "Show the product before explaining it.", "retentionStyle", "Move from broad beauty to tighter proof.", "musicStyle", "Confident modern commercial bed.", "shotTypes", List.of("Hero Shot", "Beauty Shot", "Macro Shot", "Texture Shot", "Pack Shot"));
        };
    }

    private List<String> selectedShotTypesFor(GenerateProductAdPipelineRequest request) {
        if (autoPlanShotTypes(request)) {
            return List.of();
        }
        Map<String, Object> existing = mapValue(request.existingBrief());
        Map<String, Object> direction = mapValue(existing.get("creativeDirection"));
        Map<String, Object> brand = mapValue(request.brandContext());
        List<String> selected = mergedUniqueStrings(
                stringList(direction.get("requestedShotTypes")),
                stringList(existing.get("selectedShotTypeLabels")),
                stringList(existing.get("selectedShotTypes")),
                stringList(brand.get("requestedShotTypes"))
        );
        return selected.stream()
                .map(this::humanizeCreativeValue)
                .filter(value -> !noHumans(request) || !isHumanLedShotType(value))
                .toList();
    }

    private boolean autoPlanShotTypes(GenerateProductAdPipelineRequest request) {
        if (request.autoPlanShotTypes() != null) {
            return request.autoPlanShotTypes();
        }
        Map<String, Object> existing = mapValue(request.existingBrief());
        Map<String, Object> direction = mapValue(existing.get("creativeDirection"));
        Object configured = firstValue(
                direction.get("autoPlanShotTypes"),
                existing.get("autoPlanShotTypes"),
                mapValue(request.brandContext()).get("autoPlanShotTypes")
        );
        if (configured != null) {
            return booleanValue(configured, true);
        }
        String source = firstText(direction.get("shotRecipeSource"), existing.get("shotRecipeSource"));
        return source.isBlank() || !"custom".equalsIgnoreCase(source);
    }

    private boolean noHumans(GenerateProductAdPipelineRequest request) {
        if (request.noHumans() != null) {
            return request.noHumans();
        }
        Map<String, Object> existing = mapValue(request.existingBrief());
        Map<String, Object> direction = mapValue(existing.get("creativeDirection"));
        Object configured = firstValue(
                direction.get("noHumans"),
                existing.get("noHumans"),
                existing.get("no_humans"),
                mapValue(request.brandContext()).get("noHumans")
        );
        return booleanValue(configured, false);
    }

    private boolean isHumanLedShotType(String shotType) {
        String normalized = humanizeCreativeValue(shotType).toLowerCase(Locale.ROOT);
        return containsAny(normalized, "action", "lifestyle", "testimonial", "ugc", "presenter", "person", "human", "hand");
    }

    private String noHumansNegativePrompt() {
        return "no people, no person, no face, no hands, no arms, no human body, no human silhouette, no human reflection, no presenter, no crowd";
    }

    private String mergeNegativePrompt(Object existing, String required) {
        String current = firstText(existing);
        String addition = firstText(required);
        if (current.isBlank()) return addition;
        if (addition.isBlank() || current.toLowerCase(Locale.ROOT).contains(addition.toLowerCase(Locale.ROOT))) return current;
        return current + ", " + addition;
    }

    private boolean containsAny(String value, String... candidates) {
        String normalized = defaultString(value, "").toLowerCase(Locale.ROOT);
        if (candidates == null) return false;
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private List<String> shotTypeRecipe(GenerateProductAdPipelineRequest request, Map<String, Object> product) {
        List<String> selected = new ArrayList<>(selectedShotTypesFor(request));
        if (selected.isEmpty()) {
            String productContext = String.join(" ", List.of(
                    stringValue(product == null ? null : product.get("productCategory")),
                    stringValue(product == null ? null : product.get("category")),
                    stringValue(product == null ? null : product.get("description")),
                    stringValue(product == null ? null : product.get("ingredients")),
                    stringValue(request.categoryCode()),
                    stringValue(request.productName())
            )).toLowerCase(Locale.ROOT);
            if (containsAny(productContext, "drink", "beverage", "coffee", "tea", "juice", "soda", "water", "milk")) {
                selected.addAll(List.of("Hero Shot", "Pour Shot", "Splash Shot", "Macro Shot", "Ingredient Shot", "Floating Shot", "Pack Shot"));
            } else if (containsAny(productContext, "food", "snack", "chips", "biscuit", "cookie", "chocolate", "candy", "nutrition")) {
                selected.addAll(List.of("Hero Shot", "Macro Shot", "Ingredient Shot", "Explosion Shot", "Texture Shot", "Floating Shot", "Pack Shot"));
            } else if (containsAny(productContext, "beauty", "cosmetic", "skin", "hair", "perfume", "fragrance", "makeup", "serum", "cream")) {
                selected.addAll(List.of("Hero Shot", "Beauty Shot", "Macro Shot", "Texture Shot", "Ingredient Shot", "Floating Shot", "Pack Shot"));
            } else if (containsAny(productContext, "electronic", "tech", "gadget", "phone", "laptop", "audio", "headphone", "appliance")) {
                selected.addAll(List.of("Hero Shot", "Macro Shot", "Cutaway Shot", "Assembly Shot", "Floating Shot", "Beauty Shot", "Pack Shot"));
            } else if (containsAny(productContext, "fashion", "shoe", "watch", "jewellery", "jewelry", "bag", "apparel", "accessory")) {
                selected.addAll(List.of("Hero Shot", "Beauty Shot", "Macro Shot", "Slow Motion Shot", "Floating Shot", "Texture Shot", "Pack Shot"));
            } else {
                selected.addAll(stringList(formatPlaybookFor(adFormatKeyFor(request)).get("shotTypes")));
            }
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : selected) {
            String shotType = humanizeCreativeValue(value);
            if (!noHumans(request) || !isHumanLedShotType(shotType)) {
                normalized.add(shotType);
            }
        }
        normalized.removeIf(value -> value.equalsIgnoreCase("Hero Shot") || value.equalsIgnoreCase("Pack Shot"));
        List<String> recipe = new ArrayList<>();
        recipe.add("Hero Shot");
        recipe.addAll(normalized);
        if (recipe.size() == 1) {
            recipe.addAll(List.of("Beauty Shot", "Macro Shot", "Floating Shot"));
        }
        recipe.add("Pack Shot");
        return recipe;
    }

    private List<Map<String, Object>> applyCreativeDirectionToShots(
            List<Map<String, Object>> shots,
            GenerateProductAdPipelineRequest request,
            Map<String, Object> product
    ) {
        List<Map<String, Object>> source = shots == null ? List.of() : shots;
        List<String> recipe = shotTypeRecipe(request, product);
        String adFormat = adFormatFor(request);
        Map<String, Object> formatPlaybook = formatPlaybookFor(adFormatKeyFor(request));
        String referencePolicy = String.valueOf(creativeBrief(request).get("productReferencePolicy"));
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Map<String, Object> shot = new LinkedHashMap<>(source.get(index));
            String shotType = firstText(shot.get("shotType"), shot.get("shot_type"), recipe.get(index % recipe.size()));
            if (index == 0) {
                shotType = "Hero Shot";
            } else if (index == source.size() - 1) {
                shotType = "Pack Shot";
            } else if (noHumans(request) && isHumanLedShotType(shotType)) {
                shotType = recipe.get(index % recipe.size());
            }
            shot.put("shotType", humanizeCreativeValue(shotType));
            shot.put("adFormat", adFormat);
            shot.put("adFormatKey", adFormatKeyFor(request));
            shot.putIfAbsent("formatStructure", formatPlaybook.get("structure"));
            shot.putIfAbsent("formatHookStyle", formatPlaybook.get("hookStyle"));
            shot.putIfAbsent("formatRetentionStyle", formatPlaybook.get("retentionStyle"));
            shot.putIfAbsent("productReferencePolicy", referencePolicy);
            shot.putIfAbsent("visualExecution", shotTitleForType(shotType));
            if (noHumans(request)) {
                shot.put("noHumans", true);
                shot.put("negativePrompt", mergeNegativePrompt(shot.get("negativePrompt"), noHumansNegativePrompt()));
                shot.put("dialogueMode", "OFF_SCREEN_VOICEOVER");
                shot.put("speakerVisibility", "OFF_SCREEN");
                shot.put("lipSyncRequired", false);
                shot.put("generationMode", "AI_GENERATED");
            }
            enriched.add(shot);
        }
        return enriched;
    }

    private Map<String, Object> fallbackHookPlan(
            Map<String, Object> product,
            List<Map<String, Object>> shots,
            GenerateProductAdPipelineRequest request
    ) {
        String productName = firstText(product.get("productName"), product.get("name"), request.productName(), "the product");
        Map<String, Object> brief = creativeBrief(request);
        String format = adFormatFor(request);
        Map<String, Object> formatPlaybook = formatPlaybookFor(adFormatKeyFor(request));
        String hookLens = firstText(brief.get("hookLens"), brief.get("hookBridge"));
        String firstVisual = shots == null || shots.isEmpty() ? "a premium Hero Shot" : firstText(shots.get(0).get("visualExecution"), shots.get(0).get("visual"), "a Hero Shot");
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("timing", "0-2 seconds");
        plan.put("adFormat", format);
        plan.put("adFormatKey", adFormatKeyFor(request));
        plan.put("hookLens", hookLens);
        plan.put("visual", firstVisual + " for " + productName + ". Format hook: " + formatPlaybook.get("hookStyle"));
        plan.put("voiceOrSuper", hookLineForFormat(adFormatKeyFor(request), productName, request));
        plan.put("rule", formatPlaybook.get("hookStyle") + " No generic brand intro before the first product-relevant payoff.");
        return plan;
    }

    private Map<String, Object> fallbackRetentionPlan(
            List<Map<String, Object>> shots,
            GenerateProductAdPipelineRequest request
    ) {
        List<Map<String, Object>> beats = new ArrayList<>();
        List<Map<String, Object>> source = shots == null ? List.of() : shots;
        for (int index = 0; index < source.size(); index++) {
            Map<String, Object> shot = source.get(index);
            int number = intValue(firstValue(shot.get("shotNumber"), shot.get("sceneNumber")), index + 1);
            String goal = retentionGoalForShot(index, source.size(), request);
            beats.add(Map.of(
                    "shotNumber", number,
                    "retentionGoal", goal,
                    "patternInterrupt", retentionPatternInterruptForShot(index, source.size())
            ));
        }
        Map<String, Object> plan = new LinkedHashMap<>();
        Map<String, Object> formatPlaybook = formatPlaybookFor(adFormatKeyFor(request));
        plan.put("strategy", firstText(creativeBrief(request).get("retentionStrategy"), formatPlaybook.get("retentionStyle")));
        plan.put("rhythm", String.valueOf(formatPlaybook.get("retentionStyle")) + " Introduce a new proof, visual payoff, pattern interrupt, or CTA progression every 2-4 seconds.");
        plan.put("beats", beats);
        plan.put("captionPolicy", "Keep one readable message per beat; captions reinforce the visual rather than repeat it.");
        return plan;
    }

    private List<Map<String, Object>> applyHookAndRetentionDirectionToShots(
            List<Map<String, Object>> shots,
            Map<String, Object> hookPlan,
            Map<String, Object> retentionPlan
    ) {
        List<Map<String, Object>> enriched = new ArrayList<>();
        List<Map<String, Object>> source = shots == null ? List.of() : shots;
        List<Map<String, Object>> beats = mapListValue(retentionPlan == null ? null : retentionPlan.get("beats"));
        for (int index = 0; index < source.size(); index++) {
            Map<String, Object> shot = new LinkedHashMap<>(source.get(index));
            Map<String, Object> beat = index < beats.size() ? beats.get(index) : Map.of();
            if (index == 0) {
                shot.putIfAbsent("hook", firstText(hookPlan == null ? null : hookPlan.get("voiceOrSuper")));
                shot.putIfAbsent("hookTiming", firstText(hookPlan == null ? null : hookPlan.get("timing"), "0-2 seconds"));
            }
            shot.putIfAbsent("retentionGoal", firstText(beat.get("retentionGoal"), retentionGoalForShot(index, source.size(), null)));
            shot.putIfAbsent("patternInterrupt", firstText(beat.get("patternInterrupt"), retentionPatternInterruptForShot(index, source.size())));
            enriched.add(shot);
        }
        return enriched;
    }

    private String retentionGoalForShot(int index, int totalShots, GenerateProductAdPipelineRequest request) {
        if (index <= 0) return "Stop the scroll with a specific product tension, result, or visual contrast.";
        if (index >= Math.max(1, totalShots - 1)) return "Make the pack, brand, offer, and CTA instantly readable before the viewer leaves.";
        if (index == 1) return "Reveal the product as the answer immediately after the hook.";
        if (index % 3 == 0) return "Reset attention with a tangible product transformation or unexpected visual payoff.";
        return "Add concrete product proof that earns the next beat and moves toward the CTA.";
    }

    private String retentionPatternInterruptForShot(int index, int totalShots) {
        if (index == 0) return "Open on movement, contrast, or an unexpected close product detail.";
        if (index >= Math.max(1, totalShots - 1)) return "Slow briefly for a stable product packshot and uncluttered CTA.";
        return index % 2 == 0
                ? "Change scale, camera move, or product interaction."
                : "Introduce a proof detail, texture, ingredient, or before-after contrast.";
    }

    private List<Map<String, Object>> applyCreativeDirectionToImagePrompts(
            List<Map<String, Object>> prompts,
            List<Map<String, Object>> shots,
            GenerateProductAdPipelineRequest request,
            Map<String, Object> product
    ) {
        List<Map<String, Object>> source = prompts == null ? List.of() : prompts;
        List<String> recipe = shotTypeRecipe(request, product);
        String adFormat = adFormatFor(request);
        Map<String, Object> formatPlaybook = formatPlaybookFor(adFormatKeyFor(request));
        List<Map<String, Object>> enriched = new ArrayList<>();
        for (int index = 0; index < source.size(); index++) {
            Map<String, Object> prompt = new LinkedHashMap<>(source.get(index));
            Map<String, Object> shot = index < (shots == null ? 0 : shots.size()) ? shots.get(index) : Map.of();
            String shotType = firstText(prompt.get("shotType"), shot.get("shotType"), recipe.get(index % recipe.size()));
            String direction = "Requested commercial format: " + adFormat
                    + ". Visual shot type: " + humanizeCreativeValue(shotType)
                    + ". Format structure: " + formatPlaybook.get("structure")
                    + ". Retention behavior: " + formatPlaybook.get("retentionStyle")
                    + ". Preserve the supplied product's packaging, logo, color, and physical proportions."
                    + " Use product photos as reference only, never storyboard sketches as final-product imagery.";
            String existingPrompt = firstText(prompt.get("prompt"), prompt.get("imagePrompt"));
            prompt.put("prompt", existingPrompt.isBlank()
                    ? "Create a polished 9:16 product-ad image anchor. " + direction
                    : existingPrompt + "\nCreative direction: " + direction);
            prompt.put("shotType", humanizeCreativeValue(shotType));
            prompt.put("adFormat", adFormat);
            if (noHumans(request)) {
                prompt.put("noHumans", true);
                prompt.put("negativePrompt", mergeNegativePrompt(prompt.get("negativePrompt"), noHumansNegativePrompt()));
            }
            enriched.add(prompt);
        }
        return enriched;
    }

    private List<Map<String, Object>> applyGenerationPromptsToShots(
            List<Map<String, Object>> shots,
            List<Map<String, Object>> imagePrompts,
            GenerateProductAdPipelineRequest request,
            Map<String, Object> product
    ) {
        Map<Integer, Map<String, Object>> promptByShot = new LinkedHashMap<>();
        List<Map<String, Object>> promptSource = imagePrompts == null ? List.of() : imagePrompts;
        for (int index = 0; index < promptSource.size(); index++) {
            Map<String, Object> prompt = promptSource.get(index);
            promptByShot.put(intValue(firstValue(prompt.get("shotNumber"), prompt.get("sceneNumber")), index + 1), prompt);
        }
        String productName = firstText(
                product == null ? null : product.get("productName"),
                product == null ? null : product.get("name"),
                request.productName(),
                "the supplied product"
        );
        List<Map<String, Object>> enriched = new ArrayList<>();
        List<Map<String, Object>> source = shots == null ? List.of() : shots;
        for (int index = 0; index < source.size(); index++) {
            Map<String, Object> shot = new LinkedHashMap<>(source.get(index));
            int shotNumber = intValue(firstValue(shot.get("shotNumber"), shot.get("sceneNumber")), index + 1);
            Map<String, Object> prompt = promptByShot.getOrDefault(shotNumber, index < promptSource.size() ? promptSource.get(index) : Map.of());
            String shotType = firstText(shot.get("shotType"), prompt.get("shotType"), "Hero Shot");
            String cameraMovement = firstText(
                    shot.get("cameraMovement"),
                    shot.get("cameraMove"),
                    prompt.get("cameraMovement"),
                    prompt.get("cameraMove"),
                    cameraMovementForShotType(shotType)
            );
            String imagePrompt = firstText(
                    shot.get("imagePrompt"),
                    shot.get("storyboardImagePrompt"),
                    prompt.get("prompt"),
                    prompt.get("imagePrompt"),
                    shot.get("visualPrompt"),
                    shot.get("visual")
            );
            String motionPrompt = firstText(
                    shot.get("videoPrompt"),
                    shot.get("animationPrompt"),
                    shot.get("videoMotionPrompt"),
                    prompt.get("videoMotionPrompt"),
                    prompt.get("motionPrompt")
            );
            if (motionPrompt.isBlank()) {
                motionPrompt = "Animate this " + humanizeCreativeValue(shotType).toLowerCase(Locale.ROOT)
                        + " of " + productName + " for " + intValue(shot.get("durationSeconds"), 5) + " seconds. "
                        + cameraMovement + ". Preserve the exact product, package geometry, logo, label, colors, materials, and proportions from the image anchor. "
                        + "Use physically plausible product motion, commercial lighting continuity, a stable focal subject, and a clean transition-ready ending. "
                        + "Do not add text, labels, objects, or brand marks that are not present in the approved image.";
            }
            String negativePrompt = mergeNegativePrompt(
                    firstValue(shot.get("negativePrompt"), prompt.get("negativePrompt")),
                    "no package mutation, no logo drift, no label changes, no warped product, no duplicate product, no unreadable text, no watermark"
            );
            if (noHumans(request)) {
                negativePrompt = mergeNegativePrompt(negativePrompt, noHumansNegativePrompt());
            }
            shot.put("imagePrompt", imagePrompt);
            shot.put("storyboardImagePrompt", imagePrompt);
            shot.put("cameraMovement", cameraMovement);
            shot.put("videoMotionPrompt", motionPrompt);
            shot.put("animationPrompt", motionPrompt);
            shot.put("videoPrompt", motionPrompt);
            shot.put("providerPrompt", motionPrompt);
            shot.put("negativePrompt", negativePrompt);
            shot.put("noHumans", noHumans(request));
            enriched.add(shot);
        }
        return enriched;
    }

    private String cameraMovementForShotType(String shotType) {
        String normalized = humanizeCreativeValue(shotType).toLowerCase(Locale.ROOT);
        if (normalized.contains("macro")) return "Use a slow macro slider move with shallow depth of field and a precise rack focus";
        if (normalized.contains("beauty")) return "Use a restrained 20-degree orbit with a soft light sweep across the product";
        if (normalized.contains("pour")) return "Keep the camera locked with a subtle push-in while the liquid pour remains physically accurate";
        if (normalized.contains("splash")) return "Use a high-speed lateral capture with a gentle push-through as droplets settle";
        if (normalized.contains("floating")) return "Use a smooth 180-degree orbit while the product floats without deforming";
        if (normalized.contains("explosion")) return "Use a controlled radial dolly-out as ingredients burst outward and settle around the product";
        if (normalized.contains("assembly")) return "Use a centered dolly-in while components assemble in a readable ordered sequence";
        if (normalized.contains("cutaway")) return "Use a slow lateral reveal into the product interior while preserving exterior geometry";
        if (normalized.contains("texture")) return "Use a grazing macro pan that reveals material texture under a moving highlight";
        if (normalized.contains("ingredient")) return "Use a slow top-down arc as ingredients move into a balanced composition";
        if (normalized.contains("pack")) return "Use a very slow dolly-in, then hold the final packaging and logo completely stable";
        if (normalized.contains("slow")) return "Use a stabilized slow-motion tracking move with a clean product-focused payoff";
        return "Use a slow cinematic dolly-in with subtle parallax and a controlled product light sweep";
    }

    private String shotTitleForType(String shotType) {
        String normalized = humanizeCreativeValue(shotType).toLowerCase(Locale.ROOT);
        if (normalized.contains("beauty")) return "Premium product beauty detail";
        if (normalized.contains("macro")) return "Macro product proof";
        if (normalized.contains("texture")) return "Texture and material detail";
        if (normalized.contains("ingredient")) return "Ingredient or component moment";
        if (normalized.contains("pour")) return "Controlled pour moment";
        if (normalized.contains("splash")) return "Dynamic splash payoff";
        if (normalized.contains("slow")) return "Slow-motion product payoff";
        if (normalized.contains("floating")) return "Floating product composition";
        if (normalized.contains("explosion")) return "Controlled ingredient burst";
        if (normalized.contains("assembly")) return "Assembly and reveal";
        if (normalized.contains("cutaway")) return "Interior or construction reveal";
        if (normalized.contains("action")) return "Natural product use";
        if (normalized.contains("lifestyle")) return "Real-life product moment";
        if (normalized.contains("pack")) return "Packaging, logo, and CTA lockup";
        return "Hero product reveal";
    }

    private String humanizeCreativeValue(String value) {
        String raw = defaultString(value, "").trim();
        if (raw.isBlank()) {
            return "Product Showcase";
        }
        if (raw.contains(" ")) {
            return raw;
        }
        String spaced = raw.replace('_', ' ').replace('-', ' ').trim();
        StringBuilder result = new StringBuilder();
        for (String part : spaced.split("\\s+")) {
            if (!part.isBlank()) {
                if (!result.isEmpty()) result.append(' ');
                result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        return result.toString();
    }

    private List<Map<String, Object>> fallbackConcepts(Map<String, Object> product, GenerateProductAdPipelineRequest request) {
        String name = firstText(product.get("productName"), product.get("name"), request.productName(), "the product");
        Map<String, Object> thirdConcept = noHumans(request)
                ? Map.of(
                        "lane", "Sensory Product Proof",
                        "hook", "Use product-only CGI, texture, ingredients, and motion to make the benefit of " + name + " immediately tangible.",
                        "cta", fallbackCta(request),
                        "whyItCanWork", "High-retention commercial proof without any presenter, hand, or human performance."
                )
                : Map.of(
                        "lane", "UGC/Testimonial Style",
                        "hook", "A creator explains the tiny reason they would choose " + name + ".",
                        "cta", fallbackCta(request),
                        "whyItCanWork", "Social proof tone without depending on a manual prompt."
                );
        return List.of(
                Map.of(
                        "lane", "Problem-Solution",
                        "hook", "Show the ordinary pain, then make " + name + " the clean switch.",
                        "cta", fallbackCta(request),
                        "whyItCanWork", "Direct response structure with an immediate before/after."
                ),
                Map.of(
                        "lane", "Premium Brand Story",
                        "hook", "Make " + name + " feel like the elevated version of a daily habit.",
                        "cta", fallbackCta(request),
                        "whyItCanWork", "Useful when brand trust and price justification matter."
                ),
                thirdConcept
        );
    }

    private List<Map<String, Object>> fallbackShotPlan(Map<String, Object> product, GenerateProductAdPipelineRequest request) {
        String name = firstText(product.get("productName"), product.get("name"), request.productName(), "Product");
        int total = durationSeconds(request.durationSeconds());
        int[] durations = total <= 30 ? new int[]{3, 4, 4, 5, 5, 4, 5} : new int[]{3, 5, 7, 7, 8, 8, 7, 7, 5, 3};
        List<String> recipe = shotTypeRecipe(request, product);
        String adFormat = adFormatFor(request);
        List<Map<String, Object>> shots = new ArrayList<>();
        int start = 0;
        for (int index = 0; index < durations.length; index++) {
            int duration = Math.max(1, durations[index]);
            String shotType = index == 0
                    ? "Hero Shot"
                    : index == durations.length - 1
                    ? "Pack Shot"
                    : recipe.get(index % recipe.size());
            String title = shotTitleForType(shotType);
            Map<String, Object> shot = new LinkedHashMap<>();
            shot.put("shotNumber", index + 1);
            shot.put("sceneNumber", index + 1);
            shot.put("title", title);
            shot.put("durationSeconds", duration);
            shot.put("startSeconds", start);
            shot.put("endSeconds", start + duration);
            shot.put("shotType", humanizeCreativeValue(shotType));
            shot.put("adFormat", adFormat);
            shot.put("visual", name + " " + humanizeCreativeValue(shotType).toLowerCase(Locale.ROOT) + ": " + title + ".");
            shot.put("voiceover", fallbackVoiceLine(index, name, request));
            shot.put("motion", "cinematic product motion with stable packaging and brand colors");
            shot.put("productReferencePolicy", creativeBrief(request).get("productReferencePolicy"));
            shot.put("generationMode", "AI_GENERATED");
            shot.put("noHumans", noHumans(request));
            if (noHumans(request)) {
                shot.put("dialogueMode", "OFF_SCREEN_VOICEOVER");
                shot.put("speakerVisibility", "OFF_SCREEN");
                shot.put("lipSyncRequired", false);
                shot.put("negativePrompt", noHumansNegativePrompt());
            }
            shots.add(shot);
            start += duration;
        }
        return shots;
    }

    private List<Map<String, Object>> fallbackImagePrompts(
            Map<String, Object> product,
            List<Map<String, Object>> shotPlan,
            GenerateProductAdPipelineRequest request
    ) {
        String name = firstText(product.get("productName"), product.get("name"), request.productName(), "the product");
        String colors = String.join(", ", stringList(product.get("brandColors")));
        List<Map<String, Object>> prompts = new ArrayList<>();
        List<Map<String, Object>> source = shotPlan == null || shotPlan.isEmpty()
                ? fallbackShotPlan(product, request)
                : shotPlan;
        for (int index = 0; index < Math.min(10, source.size()); index++) {
            Map<String, Object> shot = source.get(index);
            int shotNumber = intValue(firstValue(shot.get("shotNumber"), shot.get("sceneNumber")), index + 1);
            String title = firstText(shot.get("title"), "Product shot " + shotNumber);
            String shotType = firstText(shot.get("shotType"), shot.get("shot_type"), "Hero Shot");
            Map<String, Object> prompt = new LinkedHashMap<>();
            prompt.put("assetKind", slug(title));
            prompt.put("shotNumber", shotNumber);
            prompt.put("title", title);
            prompt.put("shotType", humanizeCreativeValue(shotType));
            prompt.put("adFormat", adFormatFor(request));
            prompt.put("prompt", """
                    Create a premium vertical product-ad still for %s.
                    Ad format: %s. Shot type: %s. Shot: %s.
                    Product/package must stay consistent with reference images. Use brand colors: %s.
                    Product photos are packaging reference only. Do not use storyboard sketches as final-product source imagery.
                    No random logos, no illegible claims, no distorted packaging. %s
                    Lighting: clean commercial lighting. Composition: mobile-safe 9:16.
                    """.formatted(name, adFormatFor(request), humanizeCreativeValue(shotType), title, colors.isBlank() ? "use extracted brand colors" : colors, noHumans(request) ? "No people, faces, hands, arms, bodies, silhouettes, or human reflections." : "Do not add unnecessary people or hands.").trim());
            prompt.put("videoMotionPrompt", cameraMovementForShotType(shotType) + ". Use product-safe physics and no packaging mutation.");
            prompt.put("noHumans", noHumans(request));
            prompt.put("negativePrompt", noHumans(request) ? noHumansNegativePrompt() : "no extra limbs, no distorted hands, no watermark");
            prompts.add(prompt);
        }
        return prompts;
    }

    private List<Map<String, Object>> normalizeImagePromptCount(List<Map<String, Object>> imagePrompts, GenerateProductAdPipelineRequest request) {
        int desired = imageCount(request.imageCount());
        List<Map<String, Object>> normalized = new ArrayList<>(imagePrompts == null ? List.of() : imagePrompts);
        while (normalized.size() < desired && !normalized.isEmpty()) {
            Map<String, Object> copy = new LinkedHashMap<>(normalized.get(normalized.size() % Math.max(1, imagePrompts.size())));
            copy.put("assetKind", defaultString(copy.get("assetKind"), "product_anchor") + "_" + (normalized.size() + 1));
            copy.put("title", defaultString(copy.get("title"), "Product image") + " variant " + (normalized.size() + 1));
            normalized.add(copy);
        }
        if (normalized.isEmpty()) {
            normalized = fallbackImagePrompts(Map.of(), List.of(), request);
        }
        return normalized.stream().limit(desired).toList();
    }

    private Map<String, Object> fallbackDialoguePlan(Map<String, Object> product, List<Map<String, Object>> shots, GenerateProductAdPipelineRequest request) {
        String name = firstText(product.get("productName"), product.get("name"), request.productName(), "this product");
        String voiceover = "Most choices feel the same until one detail changes the habit. " + name
                + " makes that upgrade clear, useful, and easy to act on. " + fallbackCta(request);
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("voiceoverScript", voiceover);
        plan.put("dialogueStyle", noHumans(request)
                ? firstText(requestedTone(request), product.get("adTone"), product.get("tone"), "bold off-screen commercial voiceover")
                : firstText(requestedTone(request), product.get("adTone"), product.get("tone"), "clear founder-to-customer explanation"));
        plan.put("dialogueMode", "OFF_SCREEN_VOICEOVER");
        plan.put("speakerVisibility", "OFF_SCREEN");
        plan.put("lipSyncRequired", false);
        plan.put("noHumans", noHumans(request));
        plan.put("perShotLines", fallbackSrtCues(shots, Map.of("voiceoverScript", voiceover), request));
        return plan;
    }

    private Map<String, Object> fallbackMusicPlan(GenerateProductAdPipelineRequest request, Map<String, Object> product) {
        String tone = firstText(requestedTone(request), product.get("adTone"), product.get("tone"), "confident modern commercial");
        int tempoBpm = "fast".equalsIgnoreCase(firstText(request.pacingStyle())) ? 118 : 92;
        Map<String, Object> formatPlaybook = formatPlaybookFor(adFormatKeyFor(request));
        Map<String, Object> plan = new LinkedHashMap<>();
        plan.put("adFormat", adFormatFor(request));
        plan.put("adFormatKey", adFormatKeyFor(request));
        plan.put("formatMusicStyle", formatPlaybook.get("musicStyle"));
        plan.put("mood", tone);
        plan.put("tempoBpm", tempoBpm);
        plan.put("hookSync", "Start with a clean downbeat or restrained riser in the first 0-2 seconds; do not mask the hook line.");
        plan.put("beatMap", List.of(
                Map.of("range", "0-2s", "purpose", "Hook accent with space for the opening line."),
                Map.of("range", "2-75%", "purpose", "Steady product-proof pulse with small lifts at pattern interrupts."),
                Map.of("range", "final 25%", "purpose", "Resolve under the packshot and CTA with a smooth tail.")
        ));
        plan.put("prompt", "Original royalty-safe " + tone + " product-ad bed for a " + adFormatFor(request)
                + ". Format behavior: " + formatPlaybook.get("musicStyle")
                + ". " + tempoBpm + " BPM, hook accent in the first two seconds, proof-beat lifts, no vocals, edit-friendly stems.");
        plan.put("ducking", "Duck background music under speech; keep voice dominant.");
        plan.put("fades", "Use 120-250 ms fades between segments.");
        return plan;
    }

    private Map<String, Object> fallbackFreeMusicPlan(GenerateProductAdPipelineRequest request, Map<String, Object> product) {
        String category = firstText(product.get("category"), request.categoryCode(), "product ad");
        String format = adFormatFor(request).toLowerCase(Locale.ROOT);
        String tone = firstText(requestedTone(request), product.get("adTone"), product.get("tone"), "confident modern commercial");
        return Map.of(
                "searchKeywords", List.of(
                        category + " " + format + " " + tone + " royalty free commercial",
                        category + " clean commercial hook beat no vocals",
                        category + " product proof transition music royalty free"
                ),
                "acceptableLicenses", List.of("CC0", "Pixabay Content License", "YouTube Audio Library commercial-safe", "creator-provided licensed track"),
                "licensePolicy", "Only use tracks with commercial usage allowed. Store source URL, track title, artist, and license text in the final editing plan.",
                "fallback", "Generate silent/mixed ambience if no verified free-music license is attached."
        );
    }

    private Map<String, Object> fallbackSoundDesignPlan(GenerateProductAdPipelineRequest request) {
        return Map.of(
                "audioMixStandards", audioMixStandards(),
                "dialogue", "Keep voice consistent and speech-first.",
                "ambience", "Add low scene-matched room tone under all shots.",
                "sfx", "Use small whooshes, clicks, pours, fizz, texture hits, and transitions sparingly.",
                "reverb", "Match scene space and camera distance.",
                "fades", "Smooth fades between audio segments."
        );
    }

    private List<Map<String, Object>> fallbackSrtCues(List<Map<String, Object>> shots, Map<String, Object> dialoguePlan, GenerateProductAdPipelineRequest request) {
        List<Map<String, Object>> cues = new ArrayList<>();
        List<Map<String, Object>> source = shots == null || shots.isEmpty() ? fallbackShotPlan(Map.of(), request) : shots;
        int index = 1;
        for (Map<String, Object> shot : source) {
            int start = intValue(shot.get("startSeconds"), Math.max(0, index - 1) * 5);
            int end = intValue(shot.get("endSeconds"), start + intValue(shot.get("durationSeconds"), 5));
            String line = firstText(shot.get("voiceover"), shot.get("dialogue"), shot.get("title"), "See the product in action.");
            cues.add(Map.of(
                    "index", index++,
                    "startSeconds", start,
                    "endSeconds", Math.max(start + 1, end),
                    "text", truncate(line, 110)
            ));
        }
        return cues;
    }

    private String fallbackVoiceLine(int index, String productName, GenerateProductAdPipelineRequest request) {
        return switch (index) {
            case 0 -> "Still choosing the ordinary version?";
            case 1 -> "Meet " + productName + ".";
            case 2 -> "The difference is in the details.";
            case 3 -> "Built for the way you actually use it.";
            case 4 -> "A small upgrade you can feel every day.";
            case 5 -> "Less friction. More reason to choose better.";
            case 6 -> "It fits naturally into your routine.";
            case 7 -> fallbackCta(request);
            default -> productName + " is ready when you are.";
        };
    }

    private String hookLineForFormat(String adFormatKey, String productName, GenerateProductAdPipelineRequest request) {
        return switch (defaultString(adFormatKey, "product_showcase")) {
            case "problem_solution" -> "Still doing it the hard way? " + productName + " changes that.";
            case "lifestyle" -> "This is the small part of the day " + productName + " makes better.";
            case "storytelling" -> "It started as a small decision. Then " + productName + " changed the moment.";
            case "demonstration" -> "Watch what " + productName + " can actually do.";
            case "feature_highlight" -> "The difference is one detail: " + productName + ".";
            case "comparison" -> "One choice creates friction. " + productName + " does not.";
            case "explainer" -> "Here is the simple reason " + productName + " works.";
            case "testimonial_ugc" -> "I did not expect " + productName + " to make this much difference.";
            case "emotional_brand_film" -> "Some rituals deserve to feel like yours. Meet " + productName + ".";
            case "promotional_offer" -> "The better choice is ready now: " + productName + ".";
            case "luxury_cinematic" -> "Meet the detail that changes the whole ritual: " + productName + ".";
            case "motion_graphics" -> productName + ", made to make the next move obvious.";
            case "documentary_bts" -> "This is where " + productName + " begins.";
            case "announcement_launch" -> "New from " + productName + ". Here is what changes.";
            default -> "Meet " + productName + ".";
        };
    }

    private String fallbackCta(GenerateProductAdPipelineRequest request) {
        String objective = firstText(request.campaignObjective(), "").toLowerCase(Locale.ROOT);
        if (objective.contains("lead")) {
            return "Book a demo today.";
        }
        if (objective.contains("install")) {
            return "Try it today.";
        }
        return "Order today.";
    }

    private Map<String, Object> editorHandoffPlan(
            GenerateProductAdPipelineRequest request,
            Map<String, Object> productIntelligence,
            Map<String, Object> marketResearch,
            List<Map<String, Object>> adConcepts,
            List<Map<String, Object>> shotPlan,
            List<Map<String, Object>> imagePrompts,
            Map<String, Object> dialoguePlan,
            Map<String, Object> musicPlan,
            Map<String, Object> freeMusicPlan,
            Map<String, Object> soundDesignPlan,
            Map<String, Object> videoFinishingPlan,
            Map<String, Object> videoPacingProfile,
            Map<String, Object> videoConsistencyBible,
            List<Map<String, Object>> srtCues,
            Map<String, Object> aiOutput
    ) {
        Map<String, Object> plan = firstMap(
                aiOutput.get("editorHandoffPlan"),
                aiOutput.get("editorBrief"),
                aiOutput.get("editingPlan"),
                videoFinishingPlan.get("editingPlan")
        );
        String productName = firstText(productIntelligence.get("productName"), productIntelligence.get("name"), request.productName(), "the product");
        plan.putIfAbsent("summary", "Edit the final product commercial for " + productName + " using the approved scene clips, generated product images, captions, dialogue, sound design, and music guidance.");
        plan.putIfAbsent("editorNotes", "Preserve the approved hook, retention beats, shot order, and product claims. Tighten rhythm, keep the product readable on mobile, balance voice/music/SFX, verify captions, and deliver the finished master.");
        plan.putIfAbsent("recommendedTools", recommendedEditingTools());
        plan.putIfAbsent("toolsToUse", recommendedEditingTools());
        plan.putIfAbsent("toolPolicy", "Use any equivalent professional editor, but store the tool used, project settings, source music license, and export settings in delivery notes.");
        plan.putIfAbsent("sourceAssetsRequired", List.of(
                "final generated scene clips",
                "generated product image anchors",
                "source product/reference images",
                "approved screenplay and shot plan",
                "dialogue or voiceover script",
                "SRT captions",
                "music track or verified royalty-free music source",
                "sound design plan"
        ));
        plan.putIfAbsent("assetManifest", Map.of(
                "productIntelligence", productIntelligence,
                "marketResearch", marketResearch,
                "adConcepts", adConcepts,
                "imagePrompts", imagePrompts,
                "dialoguePlan", dialoguePlan,
                "musicPlan", musicPlan,
                "freeMusicPlan", freeMusicPlan,
                "soundDesignPlan", soundDesignPlan,
                "videoConsistencyBible", videoConsistencyBible,
                "srtCues", srtCues
        ));
        plan.putIfAbsent("perShotChecklist", perShotEditorChecklist(shotPlan, srtCues));
        plan.putIfAbsent("hookAndRetentionPlan", Map.of(
                "hookPlan", firstMap(videoFinishingPlan.get("hookPlan")),
                "retentionPlan", firstMap(videoFinishingPlan.get("retentionPlan")),
                "musicBeatMap", firstList(musicPlan.get("beatMap"))
        ));
        plan.putIfAbsent("cuttingInstructions", firstText(
                videoPacingProfile.get("shotRhythm"),
                "Keep cuts clean and direct. Use faster cuts for hooks/proof beats and slightly longer holds for product readability."
        ));
        plan.putIfAbsent("captionInstructions", Map.of(
                "format", "srt",
                "policy", "Verify timing, spelling, CTA, and safe mobile placement. Burn captions only if requested by the creator flow.",
                "source", "srtFile"
        ));
        plan.putIfAbsent("audioMixStandards", audioMixStandards());
        plan.putIfAbsent("musicLicenseRequirements", Map.of(
                "sourcePlan", freeMusicPlan,
                "requiredFields", List.of("trackTitle", "artist", "sourceUrl", "licenseName", "commercialUseAllowed", "licenseTextOrScreenshotUrl"),
                "policy", "Do not use unverified copyrighted music. If license is not clear, use no music or a verified free/commercial-safe replacement."
        ));
        plan.putIfAbsent("qualityChecklist", List.of(
                "Product packaging/logo remains visually consistent across shots.",
                "No unsupported product claims or random brand marks are added.",
                "Dialogue is clear and level across the whole video.",
                "Music is ducked under speech.",
                "Room tone and ambience are present without muddying voice.",
                "SFX are sparse and support transitions/product actions.",
                "Captions match the final audio.",
                "Final export is mobile-safe and matches requested duration."
        ));
        plan.putIfAbsent("deliverables", List.of(
                "final_master_video",
                "caption_srt",
                "music_license_note",
                "edit_decision_notes",
                "revision_notes"
        ));
        plan.putIfAbsent("reviewPolicy", "two_included_revision_rounds_then_paid_changes");
        plan.putIfAbsent("handoffStatus", "READY_FOR_EDITOR_QUEUE_AFTER_FINAL_RENDER");
        return plan;
    }

    private List<Map<String, Object>> recommendedEditingTools() {
        return List.of(
                Map.of("stage", "timeline_edit", "preferred", "DaVinci Resolve or Adobe Premiere Pro", "alternatives", List.of("Final Cut Pro", "CapCut Desktop")),
                Map.of("stage", "audio_mix", "preferred", "DaVinci Fairlight or Adobe Audition", "alternatives", List.of("Audacity", "Premiere Essential Sound")),
                Map.of("stage", "captions", "preferred", "Premiere captions or CapCut captions", "alternatives", List.of("Subtitle Edit", "DaVinci Resolve captions")),
                Map.of("stage", "color_finish", "preferred", "DaVinci Resolve", "alternatives", List.of("Premiere Lumetri", "Final Cut color board")),
                Map.of("stage", "free_music_source", "preferred", "YouTube Audio Library or Pixabay Music", "alternatives", List.of("Free Music Archive", "creator-provided licensed track")),
                Map.of("stage", "asset_delivery", "preferred", "DalaiLlama asset links / MinIO signed URLs", "alternatives", List.of("Google Drive", "Dropbox"))
        );
    }

    private List<Map<String, Object>> perShotEditorChecklist(List<Map<String, Object>> shots, List<Map<String, Object>> srtCues) {
        List<Map<String, Object>> checklist = new ArrayList<>();
        List<Map<String, Object>> safeShots = shots == null ? List.of() : shots;
        for (int index = 0; index < safeShots.size(); index++) {
            Map<String, Object> shot = safeShots.get(index);
            Map<String, Object> cue = index < (srtCues == null ? 0 : srtCues.size()) ? srtCues.get(index) : Map.of();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("shotNumber", intValue(firstValue(shot.get("shotNumber"), shot.get("sceneNumber")), index + 1));
            item.put("title", firstText(shot.get("title"), shot.get("purpose"), "Shot " + (index + 1)));
            item.put("timeWindow", "%ss-%ss".formatted(
                    intValue(firstValue(shot.get("startSeconds"), cue.get("startSeconds")), index * 5),
                    intValue(firstValue(shot.get("endSeconds"), cue.get("endSeconds")), (index + 1) * 5)
            ));
            item.put("visualTask", firstText(shot.get("action"), shot.get("visual"), shot.get("composition"), "Use generated product image anchor and preserve product readability."));
            item.put("motionTask", firstText(shot.get("videoMotionPrompt"), shot.get("motionPrompt"), shot.get("cameraMovement"), "Clean product-safe motion with no packaging mutation."));
            item.put("retentionGoal", firstText(shot.get("retentionGoal"), "Keep attention with a concrete product proof or visual payoff."));
            item.put("patternInterrupt", firstText(shot.get("patternInterrupt"), "Refresh the visual language without losing product clarity."));
            item.put("dialogueOrCaption", firstText(cue.get("text"), shot.get("voiceover"), shot.get("dialogue"), shot.get("textOverlay")));
            item.put("audioTask", firstText(shot.get("soundDesign"), "Maintain dialogue-first mix with subtle ambience and sparse SFX."));
            checklist.add(item);
        }
        return checklist;
    }

    private Map<String, Object> audioMixStandards() {
        return Map.of(
                "dialogueLevel", "consistent_speech_first",
                "backgroundMusicDucking", "duck_under_speech",
                "ambientRoomTone", "scene_matched_low_bed",
                "soundEffectsUse", "small_sfx_sparingly",
                "reverbMatch", "match_scene_space_and_camera_distance",
                "fades", "smooth_fades_between_audio_segments"
        );
    }

    private String srtText(List<Map<String, Object>> cues) {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (Map<String, Object> cue : cues == null ? List.<Map<String, Object>>of() : cues) {
            int start = intValue(cue.get("startSeconds"), 0);
            int end = intValue(cue.get("endSeconds"), start + 2);
            builder.append(index++).append('\n')
                    .append(srtTime(start)).append(" --> ").append(srtTime(end)).append('\n')
                    .append(defaultString(cue.get("text"), "")).append("\n\n");
        }
        return builder.toString().trim();
    }

    private String srtTime(int seconds) {
        int safe = Math.max(0, seconds);
        int hours = safe / 3600;
        int minutes = (safe % 3600) / 60;
        int secs = safe % 60;
        return "%02d:%02d:%02d,000".formatted(hours, minutes, secs);
    }

    private String resolveUrl(URI base, String value) {
        String url = defaultString(value, "");
        if (url.isBlank()) {
            return "";
        }
        try {
            return base.resolve(url).toString();
        } catch (RuntimeException ex) {
            return url;
        }
    }

    private String decodeSearchUrl(String url) {
        String value = defaultString(url, "");
        int marker = value.indexOf("uddg=");
        if (marker >= 0) {
            String encoded = value.substring(marker + 5);
            int amp = encoded.indexOf('&');
            if (amp >= 0) {
                encoded = encoded.substring(0, amp);
            }
            try {
                return URLDecoder.decode(encoded, StandardCharsets.UTF_8);
            } catch (RuntimeException ignored) {
                return value;
            }
        }
        return value;
    }

    private String hostBrand(URI uri) {
        String host = defaultString(uri == null ? null : uri.getHost(), "");
        return host.replaceFirst("^www\\.", "");
    }

    private List<String> cleanUrls(List<String> urls) {
        return urls == null ? List.of() : urls.stream()
                .map(value -> defaultString(value, "").trim())
                .filter(value -> value.startsWith("http://") || value.startsWith("https://"))
                .distinct()
                .limit(12)
                .toList();
    }

    private List<String> mergedUniqueStrings(List<?>... lists) {
        Set<String> values = new LinkedHashSet<>();
        if (lists != null) {
            for (List<?> list : lists) {
                if (list == null) {
                    continue;
                }
                for (Object item : list) {
                    String value = stringValue(item);
                    if (!value.isBlank()) {
                        values.add(value);
                    }
                }
            }
        }
        return new ArrayList<>(values);
    }

    private List<Map<String, Object>> firstMapList(Object... values) {
        for (Object value : values) {
            List<Map<String, Object>> list = mapListValue(value);
            if (!list.isEmpty()) {
                return list;
            }
        }
        return List.of();
    }

    private List<Object> firstList(Object... values) {
        for (Object value : values) {
            if (value instanceof List<?> list && !list.isEmpty()) {
                return new ArrayList<>(list);
            }
        }
        return List.of();
    }

    private List<Map<String, Object>> mapListValue(Object value) {
        if (!(value instanceof List<?> list)) {
            return List.of();
        }
        List<Map<String, Object>> maps = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> map = mapValue(item);
            if (!map.isEmpty()) {
                maps.add(map);
            }
        }
        return maps;
    }

    private Map<String, Object> firstMap(Object... values) {
        for (Object value : values) {
            Map<String, Object> map = mapValue(value);
            if (!map.isEmpty()) {
                return new LinkedHashMap<>(map);
            }
        }
        return new LinkedHashMap<>();
    }

    private Map<String, Object> mapValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), item));
            return result;
        }
        return new LinkedHashMap<>();
    }

    private List<String> stringList(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(this::stringValue).filter(item -> !item.isBlank()).toList();
        }
        String text = stringValue(value);
        return text.isBlank() ? List.of() : List.of(text);
    }

    private Object firstValue(Object... values) {
        if (values == null) {
            return null;
        }
        for (Object value : values) {
            if (value != null && !String.valueOf(value).isBlank()) {
                return value;
            }
        }
        return null;
    }

    private String firstText(Object... values) {
        Object value = firstValue(values);
        return value == null ? "" : String.valueOf(value);
    }

    private String defaultString(Object value, String fallback) {
        return value == null || String.valueOf(value).isBlank() ? fallback : String.valueOf(value);
    }

    private String stringValue(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private void putIfText(Map<String, Object> map, String key, Object value) {
        String text = stringValue(value);
        if (!text.isBlank()) {
            map.put(key, text);
        }
    }

    private int durationSeconds(Integer value) {
        return value == null || value <= 0 ? 60 : Math.max(15, Math.min(180, value));
    }

    private int conceptCount(Integer value) {
        return value == null || value <= 0 ? 3 : Math.max(1, Math.min(5, value));
    }

    private int imageCount(Integer value) {
        return value == null || value <= 0 ? 10 : Math.max(8, Math.min(10, value));
    }

    private int intValue(Object value, int fallback) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean bool) {
            return bool;
        }
        if (value == null || String.valueOf(value).isBlank()) {
            return fallback;
        }
        String normalized = String.valueOf(value).trim().toLowerCase(Locale.ROOT);
        if (List.of("true", "1", "yes", "y", "on").contains(normalized)) return true;
        if (List.of("false", "0", "no", "n", "off").contains(normalized)) return false;
        return fallback;
    }

    private String stripTags(String html) {
        return decodeHtml(defaultString(html, "")
                .replaceAll("(?is)<script\\b.*?</script>", " ")
                .replaceAll("(?is)<style\\b.*?</style>", " ")
                .replaceAll("(?is)<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .trim());
    }

    private String decodeHtml(String value) {
        String text = defaultString(value, "");
        return text
                .replace("&amp;", "&")
                .replace("&quot;", "\"")
                .replace("&#39;", "'")
                .replace("&apos;", "'")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .trim();
    }

    private String truncate(String value, int maxLength) {
        String text = defaultString(value, "");
        if (text.length() <= maxLength) {
            return text;
        }
        return text.substring(0, Math.max(0, maxLength)) + "...";
    }

    private String slug(String value) {
        String normalized = defaultString(value, "product_asset").toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "_")
                .replaceAll("^_+|_+$", "");
        return normalized.isBlank() ? "product_asset" : truncate(normalized, 48);
    }

    private Map<String, Object> compactMap(Map<String, Object> value, int maxKeys) {
        Map<String, Object> compact = new LinkedHashMap<>();
        int count = 0;
        for (Map.Entry<String, Object> entry : (value == null ? Map.<String, Object>of() : value).entrySet()) {
            if (count++ >= maxKeys) {
                compact.put("truncated", true);
                break;
            }
            compact.put(entry.getKey(), entry.getValue());
        }
        return compact;
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception ex) {
            return String.valueOf(value);
        }
    }
}
