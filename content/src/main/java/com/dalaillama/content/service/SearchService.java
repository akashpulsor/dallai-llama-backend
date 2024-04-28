package com.dalaillama.content.service;

import com.dalaillama.content.dto.SearchRequest;
import com.dalaillama.content.dto.SearchResponse;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;
import java.util.function.Function;

public interface SearchService extends Function<SearchService.Request, SearchService.Response> {

    public SearchResponse search(SearchRequest searchRequest) throws JsonProcessingException;

    public record Response(SearchResponse searchResponse) {
    }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    @JsonClassDescription("Weather API request")
    public record Request(SearchRequest searchRequest) {

    }
}
