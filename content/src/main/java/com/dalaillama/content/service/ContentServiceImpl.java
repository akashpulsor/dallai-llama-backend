package com.dalaillama.content.service;

import com.dalaillama.content.dto.GenerateStructureRequestDto;
import com.dalaillama.content.dto.GenerateStructureResponseDto;
//import org.springframework.ai.vectorstore.VectorStore;
import com.dalaillama.content.dto.SearchResponse;
import com.dalaillama.content.dto.SearchResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

@Slf4j
@Service
public class ContentServiceImpl implements ContentService{

   //private final VectorStore vectorStore;
   private SearchService searchService;

   private ParsingService parsingService;
    public ContentServiceImpl(SearchService searchService,ParsingService parsingService) {
        //this.vectorStore = vectorStore;
        this.searchService = searchService;
        this.parsingService = parsingService;
    }
    @Override
    public List<GenerateStructureResponseDto> generateStructure(GenerateStructureRequestDto generateStructureRequestDto) {
        try {
            SearchResponse searchResponse = this.searchService.search(generateStructureRequestDto.getTopicName());
            log.info(""+searchResponse);
            List<SearchResponseDto> searchResponseDtoList = this.parsingService.parseContent(searchResponse);
            log.info("Store data in the form of embedding in postgres");
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return null;
    }
}
