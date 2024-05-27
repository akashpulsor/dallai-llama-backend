package com.dalaillama.content.service;


import com.dalaillama.content.dto.ChatResponseGenerated;
import com.dalaillama.content.dto.GenerateStructureResponseDto;
import com.dalaillama.content.dto.GeneratedArticleResponseDto;
import com.dalaillama.content.dto.SearchRequest;
import com.google.gson.Gson;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.StreamingChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.model.function.FunctionCallback;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.parser.BeanOutputParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.ai.model.function.FunctionCallbackWrapper;
import dev.langchain4j.data.document.Document;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.ChatClient;
import reactor.core.publisher.Flux;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService{

    private final OpenAiChatClient openAiChatClient;

    private final ChatClient chatClient;

    private final SearchService searchService;
    private String promptTemplate;

    private StreamingChatClient streamingChatClient;
    private String generateStructurePromptTemplate;



    @Autowired
    public ChatServiceImpl(ChatClient chatClient,OpenAiChatClient openAiChatClient,
                           @Value("${app.promptTemplate}") String promptTemplate,
                           StreamingChatClient streamingChatClient,
                           SearchService searchService,
                           @Value("${app.generate.structure.promptTemplate}") String generateStructurePromptTemplate)
    {
        this.openAiChatClient = openAiChatClient;
        this.chatClient = chatClient;
        this.promptTemplate = promptTemplate;
        this.generateStructurePromptTemplate = generateStructurePromptTemplate;
        this.searchService = searchService;
        this.streamingChatClient= streamingChatClient;
    }

    //@Override
    public ChatResponseGenerated chat(SearchRequest searchRequest) {
        BeanOutputParser<ChatResponseGenerated> parser =
                new BeanOutputParser<>(ChatResponseGenerated.class);
        String format = parser.getFormat();
        PromptTemplate pt = new PromptTemplate(promptTemplate);
        Message userMessage = pt.createMessage(Map.of("subject", searchRequest.getQuery(), "format", format));
        ChatResponse response = chatClient.call(new Prompt(List.of(userMessage),
                OpenAiChatOptions.builder().withFunction("searchFunction").build()
                ));
        Usage usage = response.getMetadata().getUsage();
        log.info("Usage: " + usage.getPromptTokens() + " " + usage.getGenerationTokens() + "; " + usage.getTotalTokens());
        return parser.parse(response.getResult().getOutput().getContent());
    }

    @Override
    public Flux<ChatResponse> chatStream(SearchRequest searchRequest) {
        BeanOutputParser<ChatResponseGenerated> parser =
                new BeanOutputParser<>(ChatResponseGenerated.class);
        String format = parser.getFormat();
        PromptTemplate pt = new PromptTemplate(promptTemplate);

        Message userMessage = pt.createMessage(Map.of("subject", searchRequest.getQuery(), "format", format));
        //ChatResponse response = chatClient.call();
        //Usage usage = response.getMetadata().getUsage();
        return this.streamingChatClient.stream(new Prompt(List.of(userMessage),
                OpenAiChatOptions.builder().withFunction("searchFunction").build()));
    }



    @Override
    public ChatResponseGenerated generateChat(int llmId, String message) {
        BeanOutputParser<ChatResponseGenerated> parser =
                new BeanOutputParser<>(ChatResponseGenerated.class);
        String format = parser.getFormat();
        PromptTemplate pt = new PromptTemplate(promptTemplate);
        Message userMessage = pt.createMessage(Map.of("subject", message, "format", format));
        ChatResponse response = chatClient.call(new Prompt(List.of(userMessage),
                OpenAiChatOptions.builder().withFunction("searchFunction").build()));

        Usage usage = response.getMetadata().getUsage();

        log.info("Usage: " + usage.getPromptTokens() + " " + usage.getGenerationTokens() + "; " + usage.getTotalTokens());
        return parser.parse(response.getResult().getOutput().getContent());
    }

    //@Override



    @Override
    public GenerateStructureResponseDto generateStructure(int llmId, String message) {
        String prompt = "You are journalist collecting information about "+message+" in the format  format JSON  with schema : {generateStructure:[{heading:\"heading\", points:[a, b ,c]}]}";
        String generation = chatClient.call(prompt);
        Gson gson = new Gson();
        return gson.fromJson(generation,GenerateStructureResponseDto.class);
    }

    @Override
    public GeneratedArticleResponseDto generateArticle(int llmId, String topicName, String Structure) {
        String prompt = "As a Journalist write 800 word article about topic "+ topicName+" with  information: "+Structure+" then give interesting title to the content you have created, response should be in the format JSON  with schema : {title:\"heading\", body:\"a\"}";
        String generation = chatClient.call(prompt);
        Gson gson = new Gson();
        return gson.fromJson(generation,GeneratedArticleResponseDto.class);
    }



}
