package com.dalaillama.content.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import lombok.Data;

@Data
public class SearchRequest {

    //@JsonProperty(value = "location")
    //@JsonPropertyDescription("The city and state e.g. San Francisco, CA")
    private String location;

    //@JsonProperty(value = "lat")
    //@JsonPropertyDescription("The city latitude")
    private double lat;

    //@JsonProperty( value = "lon")
    //@JsonPropertyDescription("The city longitude")
    private double lon;

    //@JsonProperty(required = true, value = "query")
    //@JsonPropertyDescription("Search query")
    private String query;

    //@JsonProperty( value = "language")
    //@JsonPropertyDescription("language of query")
    private String language;

    //@JsonProperty(required = true, value = "llmId")
    //@JsonPropertyDescription("LLM you want to choose")
    private int  llmId;
}
