package com.dalaillama.content.service;

import com.dalaillama.content.dto.*;
//import org.springframework.ai.vectorstore.VectorStore;
import com.fasterxml.jackson.core.JsonProcessingException;

import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;

import javax.print.Doc;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

@Slf4j
@Service
public class ContentServiceImpl implements ContentService{

   //private final VectorStore vectorStore;
   private SearchService searchService;

   private ParsingService parsingService;
   //private VectorStore vectorStore;  VectorStore vectorStore,
   private ChatService chatService;
   private NlpService nlpService;

    public ContentServiceImpl(SearchService searchService,ParsingService parsingService,
                              ChatService chatService, NlpService nlpService) {
        //this.vectorStore = vectorStore;
        this.searchService = searchService;
        this.parsingService = parsingService;
        this.chatService = chatService;
        this.nlpService = nlpService;
    }
    @Override
    public GenerateStructureResponseDto generateStructure(GenerateStructureRequestDto generateStructureRequestDto) {
        try {
            SearchResponse searchResponse = this.searchService.search(generateStructureRequestDto.getTopicName());
            log.info(""+searchResponse);
            //List<SearchResponseDto> searchResponseDtoList = this.parsingService.parseContent(searchResponse);
            //summarizeData(searchResponseDtoList);
            /*
            List<Document> dataList = searchResponseDtoList.parallelStream().
                    map(this::createDocument).
                    collect(Collectors.toList());
            vectorStore.add(dataList);
            List<Document> documentList=vectorStore.similaritySearch(SearchRequest.query(generateStructureRequestDto.getTopicName()).withTopK(10));
           */
            log.info("Store data in the form of embedding in postgres");
            return this.chatService.generateStructure(generateStructureRequestDto.getLlmId(),generateStructureRequestDto.getTopicName());
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public GeneratedArticleResponseDto generateArticle(GenerateArticleRequestDto generateStructureRequestDto) {
        return this.chatService.generateArticle(generateStructureRequestDto.getLlmId(), generateStructureRequestDto.getTopicName(),new Gson().toJson(generateStructureRequestDto));
    }

    private List<SearchResponseDto> summarizeData(List<SearchResponseDto> searchResponseDtoList){
        for (SearchResponseDto searchResponseDto:searchResponseDtoList) {
            Map<String, String> summaryMap = this.nlpService.summarizeText(searchResponseDto.getContent().getTitle(),
                    searchResponseDto.getContent().getBody());
            searchResponseDto.
                    getContent().
                    setBody(summaryMap.
                            get(searchResponseDto.
                                    getContent().
                                    getTitle()));
            log.info(summaryMap.toString());
        }
        return searchResponseDtoList;
    }

    private Document createDocument(SearchResponseDto searchResponseDto){
        return new Document("tile: "+searchResponseDto.getContent().getTitle()+
                "Body: "+searchResponseDto.getContent().getBody()
                );
    }
}
