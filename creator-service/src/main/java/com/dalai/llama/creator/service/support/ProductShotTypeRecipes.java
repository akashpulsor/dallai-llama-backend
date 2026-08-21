package com.dalai.llama.creator.service.support;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * Deterministic, whole-video shot-type recipe planning, ported from the standalone product-ad
 * pipeline's {@code ProductAdResearchService.shotTypeRecipe}/{@code applyCreativeDirectionToShots}
 * so the "official" idea -> script -> shot-plan -> storyboard -> video pipeline
 * ({@code ProductionPlanTagService}) gets the same hero-first/pack-last, category-aware,
 * round-robin shot-type variety - computed once per script, before any per-shot AI call, so
 * adjacent shots are guaranteed different types by construction rather than left to chance.
 */
public final class ProductShotTypeRecipes {

    private ProductShotTypeRecipes() {
    }

    public static List<String> computeRecipe(
            List<String> explicitSelectedShotTypes,
            String categoryProbeText,
            List<String> fallbackShotTypes,
            boolean noHumans
    ) {
        List<String> selected = new ArrayList<>(explicitSelectedShotTypes == null ? List.of() : explicitSelectedShotTypes);
        if (selected.isEmpty()) {
            if (containsAny(categoryProbeText, "drink", "beverage", "coffee", "tea", "juice", "soda", "water", "milk")) {
                selected.addAll(List.of("Hero Shot", "Pour Shot", "Splash Shot", "Macro Shot", "Ingredient Shot", "Floating Shot", "Pack Shot"));
            } else if (containsAny(categoryProbeText, "food", "snack", "chips", "biscuit", "cookie", "chocolate", "candy", "nutrition")) {
                selected.addAll(List.of("Hero Shot", "Macro Shot", "Ingredient Shot", "Explosion Shot", "Texture Shot", "Floating Shot", "Pack Shot"));
            } else if (containsAny(categoryProbeText, "beauty", "cosmetic", "skin", "hair", "perfume", "fragrance", "makeup", "serum", "cream")) {
                selected.addAll(List.of("Hero Shot", "Beauty Shot", "Macro Shot", "Texture Shot", "Ingredient Shot", "Floating Shot", "Pack Shot"));
            } else if (containsAny(categoryProbeText, "electronic", "tech", "gadget", "phone", "laptop", "audio", "headphone", "appliance")) {
                selected.addAll(List.of("Hero Shot", "Macro Shot", "Cutaway Shot", "Assembly Shot", "Floating Shot", "Beauty Shot", "Pack Shot"));
            } else if (containsAny(categoryProbeText, "fashion", "shoe", "watch", "jewellery", "jewelry", "bag", "apparel", "accessory")) {
                selected.addAll(List.of("Hero Shot", "Beauty Shot", "Macro Shot", "Slow Motion Shot", "Floating Shot", "Texture Shot", "Pack Shot"));
            } else {
                selected.addAll(fallbackShotTypes == null ? List.of() : fallbackShotTypes);
            }
        }

        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : selected) {
            String shotType = humanizeCreativeValue(value);
            if (!noHumans || !isHumanLedShotType(shotType)) {
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

    /**
     * recipe.get(index % recipe.size()) round-robin, index 0 forced to Hero Shot and the last
     * shot forced to Pack Shot - matches ProductAdResearchService.applyCreativeDirectionToShots.
     * llmSuppliedShotType wins over the recipe when present (except at the forced first/last
     * indices), same priority order as the standalone pipeline.
     */
    public static String assignForIndex(List<String> recipe, int index, int totalShots, String llmSuppliedShotType, boolean noHumans) {
        if (recipe == null || recipe.isEmpty()) {
            return "";
        }
        String fallback = recipe.get(Math.floorMod(index, recipe.size()));
        String shotType = llmSuppliedShotType != null && !llmSuppliedShotType.isBlank()
                ? humanizeCreativeValue(llmSuppliedShotType)
                : fallback;
        if (index == 0) {
            shotType = "Hero Shot";
        } else if (totalShots > 0 && index == totalShots - 1) {
            shotType = "Pack Shot";
        } else if (noHumans && isHumanLedShotType(shotType)) {
            shotType = isHumanLedShotType(fallback) ? "Hero Shot" : fallback;
        }
        return shotType;
    }

    public static boolean isHumanLedShotType(String shotType) {
        return containsAny(humanizeCreativeValue(shotType), "action", "lifestyle", "testimonial", "ugc", "presenter", "person", "human", "hand");
    }

    public static String humanizeCreativeValue(String value) {
        String raw = value == null ? "" : value.trim();
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
                if (!result.isEmpty()) {
                    result.append(' ');
                }
                result.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1));
            }
        }
        return result.toString();
    }

    private static boolean containsAny(String value, String... candidates) {
        String normalized = value == null ? "" : value.toLowerCase(Locale.ROOT);
        if (candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate != null && !candidate.isBlank() && normalized.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
