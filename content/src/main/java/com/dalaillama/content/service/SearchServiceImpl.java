package com.dalaillama.content.service;

import com.dalaillama.content.dto.JinaResponse;
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
import dev.langchain4j.agent.tool.Tool;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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

    @Autowired
    private RestTemplate restTemplate;

    //@Override
    public SearchResponse search1(SearchRequest searchRequest) throws JsonProcessingException,SerpApiSearchException {
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



    @Override
    public JinaResponse search(SearchRequest searchRequest) throws JsonProcessingException,SerpApiSearchException {
        String url = "https://s.jina.ai/"+ searchRequest.getQuery();
        HttpHeaders headers = new HttpHeaders();
        headers.set("Accept", "application/json");
        headers.set("X-With-Generated-Alt", "true");
        headers.set("X-With-Images-Summary", "true");
        //
        // Create an HttpEntity with the headers
        HttpEntity<String> entity = new HttpEntity<>(headers);

        // Send the GET request
        ResponseEntity<JinaResponse> response = restTemplate.exchange(url, HttpMethod.GET, entity, JinaResponse.class);
        String data = "";
        return response.getBody();
    }




    public SearchServiceImpl.Response apply(Request request) {
        try {
            JinaResponse jinaResponse = search(request.searchRequest());
            log.info("Jina response: {}", jinaResponse);
            return new Response(jinaResponse);
        } catch (JsonProcessingException e) {
            log.error("Search query failed", e);
            throw new SearchResponseException("Search query failed",e);
        }
    }


}
