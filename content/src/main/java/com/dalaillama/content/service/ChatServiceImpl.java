package com.dalaillama.content.service;


import com.dalaillama.content.dto.ChatResponseGenerated;
import com.dalaillama.content.dto.GenerateStructureResponseDto;
import com.dalaillama.content.dto.GeneratedArticleResponseDto;
import com.google.gson.Gson;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.ai.chat.Generation;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.ai.parser.BeanOutputParser;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.ai.chat.ChatClient;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class ChatServiceImpl implements ChatService{

    private final OpenAiChatClient openAiChatClient;

    private final ChatClient chatClient;

    private String promptTemplate;


    private String generateStructurePromptTemplate;


    @Autowired
    public ChatServiceImpl(ChatClient chatClient,OpenAiChatClient openAiChatClient,
                           @Value("${app.promptTemplate}") String promptTemplate,
                           @Value("${app.generate.structure.promptTemplate}") String generateStructurePromptTemplate)
    {
        this.openAiChatClient = openAiChatClient;
        this.chatClient = chatClient;
        this.promptTemplate = promptTemplate;
        this.generateStructurePromptTemplate = generateStructurePromptTemplate;
    }

    @Override
    public ChatResponseGenerated generateChat(int llmId, String message) {
        BeanOutputParser<ChatResponseGenerated> parser =
                new BeanOutputParser<>(ChatResponseGenerated.class);
        String format = parser.getFormat();
        PromptTemplate pt = new PromptTemplate(promptTemplate);
        Prompt renderedPrompt = pt.create(Map.of("subject", message, "format", format));
        ChatResponse response =  chatClient.call(renderedPrompt);
        Usage usage = response.getMetadata().getUsage();

        log.info("Usage: " + usage.getPromptTokens() + " " + usage.getGenerationTokens() + "; " + usage.getTotalTokens());
        return parser.parse(response.getResult().getOutput().getContent());
    }

    //@Override
    public GenerateStructureResponseDto generateStructure1(int llmId, String message) {
        BeanOutputParser<GenerateStructureResponseDto> parser =
                new BeanOutputParser<>(GenerateStructureResponseDto.class);
        String format = parser.getFormat();
        PromptTemplate pt = new PromptTemplate(generateStructurePromptTemplate);
        Prompt renderedPrompt = pt.create(Map.of("Topic", message, "format", format));
        Generation generation = chatClient.call(renderedPrompt).getResult();
        GenerateStructureResponseDto actorsFilms = parser.parse(generation.getOutput().getContent());
        //log.info("Usage: " + usage.getPromptTokens() + " " + usage.getGenerationTokens() + "; " + usage.getTotalTokens());
        //log.info(response.getResult().getOutput().getContent());
        return actorsFilms;
    }

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
