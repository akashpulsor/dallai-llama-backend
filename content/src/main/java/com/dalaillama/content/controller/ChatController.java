package com.dalaillama.content.controller;

import com.dalaillama.content.dto.ChatResponseGenerated;
import com.dalaillama.content.dto.JinaResponse;
import com.dalaillama.content.dto.SearchRequest;
import com.dalaillama.content.dto.SearchResponse;
import com.dalaillama.content.service.Assistant;
import com.dalaillama.content.service.ChatService;
import com.dalaillama.content.service.ContentService;
import com.dalaillama.content.service.SearchService;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.output.Response;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.TokenStream;
import org.springframework.ai.chat.ChatResponse;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.function.Consumer;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private final ChatService chatService;
    private final ChatLanguageModel chatLanguageModel;
    private final OpenAiStreamingChatModel openAiStreamingChatModel;

    private final SearchService searchService;
    public ChatController(ChatService chatService,ChatLanguageModel chatLanguageModel,
                          OpenAiStreamingChatModel openAiStreamingChatModel,SearchService searchService) {
        this.chatService = chatService;
        this.chatLanguageModel = chatLanguageModel;
        this.openAiStreamingChatModel=openAiStreamingChatModel;
        this.searchService=searchService;
    }

    @GetMapping("/generate")
    public ChatResponseGenerated generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        return this.chatService.generateChat(1,message);
    }

    @PostMapping("/chat")
    public ChatResponseGenerated chat( @RequestBody SearchRequest searchRequest) {
        return this.chatService.chat(searchRequest);
    }

    @PostMapping(value="/chat/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestBody SearchRequest searchRequest) {

        Assistant assistant = AiServices.builder(Assistant.class)
                .streamingChatLanguageModel(this.openAiStreamingChatModel)
                .tools(new Tools())
                .build();
        TokenStream tokenStream = assistant.chat(searchRequest.getQuery());
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        tokenStream.onNext(sink::tryEmitNext)
                .onError(sink::tryEmitError)
                .start();

        return sink.asFlux();
    }

    @PostMapping(value="/chat/langchain")
    public ChatResponseGenerated chatLangchain(@RequestBody SearchRequest searchRequest) {

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(this.chatLanguageModel)
                .tools(new Tools())

                .build();
         //assistant.answer(searchRequest.getQuery());


        return assistant.search(searchRequest.getQuery());
    }

    @PostMapping(value="/chat/langchain/stream",produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatLangchainStream(@RequestBody SearchRequest searchRequest) {

        Assistant assistant = AiServices.builder(Assistant.class)
                .chatLanguageModel(this.chatLanguageModel)
                .tools(new Tools())
                .build();
        Sinks.Many<String> sink = Sinks.many().unicast().onBackpressureBuffer();
        TokenStream tokenStream = assistant.searchStream(searchRequest.getQuery());
        tokenStream.onNext(sink::tryEmitNext)
                .onError(sink::tryEmitError)
                .start();

        return sink.asFlux();
    }


    class Tools {

        @Tool
        JinaResponse search(String query) {
            String url = "https://s.jina.ai/"+ query;
            HttpHeaders headers = new HttpHeaders();
            headers.set("Accept", "application/json");
            headers.set("X-With-Generated-Alt", "true");
            headers.set("X-With-Images-Summary", "true");
            //
            // Create an HttpEntity with the headers
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Send the GET request
            ResponseEntity<JinaResponse> response = new RestTemplate().exchange(url, HttpMethod.GET, entity, JinaResponse.class);
            String data = "";
            return response.getBody();
        }

    }
}
