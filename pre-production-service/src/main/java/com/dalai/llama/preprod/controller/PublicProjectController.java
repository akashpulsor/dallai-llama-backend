package com.dalai.llama.preprod.controller;

import com.dalai.llama.preprod.dto.PublicChatMessageView;
import com.dalai.llama.preprod.dto.PublicProjectPackageView;
import com.dalai.llama.preprod.dto.SendPublicChatMessageRequest;
import com.dalai.llama.preprod.service.PublicProjectService;
import com.dalai.llama.preprod.service.chat.ChatServiceClient;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Deliberately unauthenticated -- see SecurityConfig's {@code /v1/public/**} carve-out. Possession
 * of the (unguessable) {@code token} is the authorization, same convention as creative-planning-
 * service's PublicProjectRequirementController. This is the client's full review page: the locked
 * (or about-to-be-locked) creative package, plus the chat box once it's locked.
 */
@RestController
public class PublicProjectController {

    private final PublicProjectService publicProjectService;

    public PublicProjectController(PublicProjectService publicProjectService) {
        this.publicProjectService = publicProjectService;
    }

    @GetMapping("/v1/public/projects/{token}")
    public ResponseEntity<PublicProjectPackageView> view(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.view(token));
    }

    @PostMapping("/v1/public/projects/{token}/lock")
    public ResponseEntity<PublicProjectPackageView> lock(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.lock(token));
    }

    @PostMapping("/v1/public/projects/{token}/chat")
    public ResponseEntity<PublicChatMessageView> chat(@PathVariable String token, @Valid @RequestBody SendPublicChatMessageRequest request) {
        return ResponseEntity.ok(toView(publicProjectService.chat(token, request.content())));
    }

    @GetMapping("/v1/public/projects/{token}/chat")
    public ResponseEntity<List<PublicChatMessageView>> chatHistory(@PathVariable String token) {
        return ResponseEntity.ok(publicProjectService.chatHistory(token).stream().map(this::toView).collect(Collectors.toList()));
    }

    private PublicChatMessageView toView(ChatServiceClient.ChatMessageView message) {
        return new PublicChatMessageView(message.role(), message.content());
    }
}
