package com.dalai.llama.preprod.service.continuity;

/** A visual property a step shot carries over from its reference image unless the shot itself asks
 * for a change. Adding a property (season, props, vehicles...) is one constant here: the analysis
 * template is sent this list, and the resolver, prompt block and UI read it generically. */
public enum VisualField {
    TIME_OF_DAY("Time of day", "change the time of day"),
    LIGHTING("Lighting", "relight the environment or change the direction, colour or intensity of its light"),
    WEATHER("Weather", "introduce a different weather condition"),
    SEASON("Season", "change the season"),
    LOCATION("Location", "change the location"),
    ARCHITECTURE("Architecture", "replace or redesign the architecture"),
    ENVIRONMENT("Environment", "change the background or surroundings"),
    COLOUR_GRADE("Colour grade", "replace the established colour palette or grade"),
    EXPOSURE("Exposure", "brighten or darken the overall exposure"),
    ATMOSPHERE("Atmosphere", "change the atmosphere (haze, fog, smoke, mood)"),
    WARDROBE("Wardrobe", "change what the people who stay are wearing"),
    PROPS("Props", "remove or swap the props that stay in frame"),
    VEHICLES("Vehicles", "swap the vehicles that stay in frame");

    private final String label;
    private final String forbiddenChange;

    VisualField(String label, String forbiddenChange) {
        this.label = label;
        this.forbiddenChange = forbiddenChange;
    }

    public String label() {
        return label;
    }

    /** "Do not ..." phrasing for the negative continuity list. */
    public String forbiddenChange() {
        return forbiddenChange;
    }
}
