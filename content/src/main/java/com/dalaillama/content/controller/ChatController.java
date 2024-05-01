package com.dalaillama.content.controller;

import com.dalaillama.content.dto.ChatResponseGenerated;
import com.dalaillama.content.dto.SearchRequest;
import com.dalaillama.content.dto.SearchResponse;
import com.dalaillama.content.service.ChatService;
import com.dalaillama.content.service.ContentService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@RequestMapping("/api/ai")
public class ChatController {

    private final ChatService chatService;
    public ChatController(ChatService chatService) {
        this.chatService = chatService;
    }

    @GetMapping("/generate")
    public ChatResponseGenerated generate(@RequestParam(value = "message", defaultValue = "Tell me a joke") String message) {
        return this.chatService.generateChat(1,message);
    }

    @PostMapping("/chat")
    public ChatResponseGenerated chat( @RequestBody SearchRequest searchRequest) {
        return this.chatService.chat(searchRequest);
    }
}
