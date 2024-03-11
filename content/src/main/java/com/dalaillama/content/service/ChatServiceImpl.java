package com.dalaillama.content.service;

import org.springframework.ai.openai.OpenAiChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class ChatServiceImpl implements ChatService{

    private final OpenAiChatClient chatClient;

    @Autowired
    public ChatServiceImpl(OpenAiChatClient chatClient) {
        this.chatClient = chatClient;
    }
    @Override
    public Map generateChat(int llmId, String message) {
        return Map.of("generation", chatClient.call(message));
    }
}
