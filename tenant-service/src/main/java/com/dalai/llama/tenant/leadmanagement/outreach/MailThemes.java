package com.dalai.llama.tenant.leadmanagement.outreach;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

/** The product themes a piece of text is about ("Jewellery unboxing" -> jewellery, gifting), so an
 * inspiration video can be matched to a brand that sells the same kind of thing: the video's title
 * against the brand's category, name and website. A theme is a list of word stems; a stem matches the
 * whole word when short ("tea" is not "teaser"; plurals count), the start of a word when 5+ letters
 * ("perfume" in "perfumery"), and also inside a long run-together name or domain ("botaniclove" is
 * skincare). */
public final class MailThemes {

    private static final Map<String, List<String>> THEMES = new LinkedHashMap<>();

    static {
        THEMES.put("jewellery", List.of("jewel", "jewelry", "silver", "gold", "earring", "necklace", "bangle", "diamond", "bracelet", "anklet"));
        THEMES.put("ethnic", List.of("ethnic", "saree", "sari", "kurta", "kurti", "chikan", "lehenga", "handloom", "kaftan", "festive", "khadi", "block print"));
        THEMES.put("womenswear", List.of("womenswear", "women", "dress", "blouse", "kaftan", "lookbook"));
        THEMES.put("menswear", List.of("men", "mens", "trouser", "tailor", "suit", "formal"));
        THEMES.put("streetwear", List.of("streetwear", "t-shirt", "tshirt", "tee", "hoodie", "drop"));
        THEMES.put("footwear", List.of("shoe", "footwear", "sneaker", "sandal", "slipper", "chappal", "heels", "boots"));
        THEMES.put("kids", List.of("kid", "baby", "toddler", "child", "toy", "play school", "mom", "mama"));
        THEMES.put("activewear", List.of("activewear", "gym", "fitness", "sport", "athlet", "yoga", "workout", "running"));
        THEMES.put("skincare", List.of("skin", "face", "serum", "glow", "mask", "mist", "botanic", "derma", "sunscreen", "moistur", "skincare", "facewash"));
        THEMES.put("haircare", List.of("hair", "haircare", "shampoo"));
        THEMES.put("makeup", List.of("makeup", "make-up", "lipstick", "cosmetic", "kajal", "nail", "lash"));
        THEMES.put("fragrance", List.of("fragrance", "perfume", "attar", "scent", "parfum", "aroma"));
        THEMES.put("bodycare", List.of("body", "bath", "soap", "polish"));
        THEMES.put("coffee", List.of("coffee", "brew", "espresso", "cafe", "kaffa"));
        THEMES.put("tea", List.of("tea", "chai", "matcha", "kombucha"));
        THEMES.put("drinks", List.of("juice", "soda", "sparkling", "lemonade", "drink", "beverage", "kombucha", "booch"));
        THEMES.put("snacks", List.of("snack", "namkeen", "chips", "popcorn", "peanut", "makhana", "crunch", "nuts", "bhujia"));
        THEMES.put("sweets", List.of("chocolate", "cocoa", "dessert", "sweet", "cake", "brownie", "bakery", "cookie", "honey", "mithai", "ice cream",
                "mochi", "candy", "treat"));
        THEMES.put("pantry", List.of("sauce", "achar", "pickle", "masala", "spice", "oil", "ghee", "chutney", "jam", "ferment", "food", "foods"));
        THEMES.put("nutrition", List.of("protein", "supplement", "nutri", "millet", "oats", "granola", "superfood", "vitamin", "healthy"));
        THEMES.put("wellness", List.of("wellness", "ayurved", "herbal", "hemp", "health", "retreat", "femi", "period", "menstru"));
        THEMES.put("home", List.of("home", "decor", "furniture", "interior", "lighting", "lamp", "carpet", "rug", "linen", "kitchen", "living",
                "cushion", "vase", "bedding", "space"));
        THEMES.put("pet", List.of("pet", "dog", "cat", "paw", "woof", "pup", "kitty", "meow"));
        THEMES.put("gifting", List.of("gift", "hamper", "unboxing", "box"));
        THEMES.put("gadgets", List.of("tech", "gadget", "phone", "headphone", "earbud", "smartwatch", "charger", "audio", "speaker", "electron", "mouse"));
        THEMES.put("travelgear", List.of("luggage", "backpack", "travel", "bag", "handbag", "tote", "purse", "duffle", "suitcase"));
    }

    /** Directory categories that name a theme outright (the research sheet's "Category" column). */
    private static final Map<String, List<String>> CATEGORIES = Map.of(
            "jewellery", List.of("jewellery"), "footwear", List.of("footwear", "travelgear"), "kids", List.of("kids"), "toys", List.of("kids"),
            "pet", List.of("pet"), "fragrance", List.of("fragrance"), "home", List.of("home"), "wellness", List.of("wellness"),
            "sports", List.of("activewear"), "electronics", List.of("gadgets"));

    private static final Pattern WORD = Pattern.compile("[a-z][a-z-]*");

    private MailThemes() {
    }

    /** The themes of free text: a video title, a brand's name, category or website. */
    public static Set<String> of(String text) {
        Set<String> themes = new TreeSet<>();
        if (text == null || text.isBlank()) return themes;
        String lower = text.toLowerCase(Locale.ROOT);
        List<String> words = WORD.matcher(lower).results().map(m -> m.group()).toList();
        String spaced = " " + String.join(" ", words) + " ";
        for (var theme : THEMES.entrySet()) {
            if (theme.getValue().stream().anyMatch(stem -> matches(stem, words, spaced))) themes.add(theme.getKey());
        }
        for (var category : CATEGORIES.entrySet()) {
            if (words.contains(category.getKey())) themes.addAll(category.getValue());
        }
        return themes;
    }

    private static boolean matches(String stem, List<String> words, String spaced) {
        if (stem.contains(" ")) return spaced.contains(" " + stem + " ");
        if (stem.length() < 5) return words.stream().anyMatch(w -> w.equals(stem) || w.equals(stem + "s") || w.equals(stem + "es"));
        // Inside a word only for a long run-together name or domain ("thecoconutpeople"), not "retreat" for "treat".
        return words.stream().anyMatch(w -> w.startsWith(stem) || w.length() >= 8 && w.replace("-", "").contains(stem.replace("-", "")));
    }

    /** How many themes two sets share. */
    public static int overlap(Set<String> a, Set<String> b) {
        int n = 0;
        for (String theme : a) if (b.contains(theme)) n++;
        return n;
    }

}
