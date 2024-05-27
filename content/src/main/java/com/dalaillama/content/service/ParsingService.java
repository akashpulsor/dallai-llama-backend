package com.dalaillama.content.service;

import com.dalaillama.content.dto.SearchResponse;
import com.dalaillama.content.dto.SearchResponseDto;

import java.util.List;

public interface ParsingService {



    List<SearchResponseDto> parseContent(SearchResponse searchResponse);
}
