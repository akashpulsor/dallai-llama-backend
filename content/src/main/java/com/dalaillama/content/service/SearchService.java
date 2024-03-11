package com.dalaillama.content.service;

import com.dalaillama.content.dto.SearchResponse;
import com.fasterxml.jackson.core.JsonProcessingException;

import java.util.List;

public interface SearchService {

    SearchResponse search(String topic) throws JsonProcessingException;
}
