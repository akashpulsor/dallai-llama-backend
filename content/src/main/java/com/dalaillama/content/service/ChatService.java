package com.dalaillama.content.service;

import com.dalaillama.content.dto.ChatResponseGenerated;
import com.dalaillama.content.dto.GenerateStructureResponseDto;
import com.dalaillama.content.dto.GeneratedArticleResponseDto;
import com.dalaillama.content.dto.SearchRequest;

import java.util.Map;

public interface ChatService {

    ChatResponseGenerated generateChat(int llmId, String message);

    GenerateStructureResponseDto generateStructure(int llmId, String message);

    GeneratedArticleResponseDto generateArticle(int llmId, String topicName, String Structure);

    ChatResponseGenerated chat(SearchRequest searchRequest);

}
