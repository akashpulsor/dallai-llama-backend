package com.dalaillama.content.service;

import com.dalaillama.content.dto.SearchResponse;
import com.dalaillama.content.dto.SearchResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.hw.serpapi.GoogleSearch;
import com.hw.serpapi.SerpApiSearchException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;


@Service
public class SearchServiceImpl implements SearchService{


    @Override
    public SearchResponse search(String topic) throws JsonProcessingException,SerpApiSearchException {
        Map<String, String> parameter = new HashMap<>();
        parameter.put("q", topic);
        parameter.put("location", "India");
        parameter.put("hl", "en");
        parameter.put("gl", "in");
        parameter.put("engine", "duckduckgo");
        //parameter.put("google_domain", "google.com");
        parameter.put("api_key", "262b581dac1cc55105c69261270f183127d0e361e3bdd6d57579173b94a80204");

        GoogleSearch search = new GoogleSearch(parameter);
        ObjectMapper mapper = new ObjectMapper().configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);;
        String data = search.getJson().toString();
        return mapper.readValue(data, SearchResponse.class);
    }
}
