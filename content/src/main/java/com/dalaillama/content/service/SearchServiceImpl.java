package com.dalaillama.content.service;

import com.dalaillama.content.dto.SearchRequest;
import com.dalaillama.content.dto.SearchResponse;
import com.dalaillama.content.dto.SearchResponseDto;
import com.dalaillama.content.exception.SearchResponseException;
import com.fasterxml.jackson.annotation.JsonClassDescription;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonPropertyDescription;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hw.serpapi.GoogleSearch;
import com.hw.serpapi.SerpApiSearchException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.util.function.Function;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Slf4j
@Service
public class SearchServiceImpl implements  SearchService{

    @Value("${spring.serp.api-key}")
    private String searchKey;


    @Override
    public SearchResponse search(SearchRequest searchRequest) throws JsonProcessingException,SerpApiSearchException {
        Map<String, String> parameter = new HashMap<>();
        parameter.put("q", searchRequest.getQuery());
        //parameter.put("location", searchRequest.getLocation());
        //parameter.put("hl", searchRequest.getLanguage());
        //parameter.put("kl", "us-en");
        parameter.put("location", "India");
        parameter.put("hl", "en");
        parameter.put("gl", "in");
        parameter.put("engine", "duckduckgo");
        parameter.put("api_key", searchKey);
        GoogleSearch search = new GoogleSearch(parameter);
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);;
        String data = search.getJson().toString();
        return mapper.readValue(data, SearchResponse.class);
    }




    public SearchServiceImpl.Response apply(Request request) {
        try {
            SearchResponse searchResponse = search(request.searchRequest());
            return new Response(searchResponse);
        } catch (JsonProcessingException e) {
            log.error("Search query failed", e);
            throw new SearchResponseException("Search query failed",e);
        }
    }


}
