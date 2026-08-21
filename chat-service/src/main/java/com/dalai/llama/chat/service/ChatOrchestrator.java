package com.dalai.llama.chat.service;

import com.dalai.llama.chat.domain.ChatActionStatus;
import com.dalai.llama.chat.domain.ChatActionType;
import com.dalai.llama.chat.domain.ChatRole;
import com.dalai.llama.chat.domain.entity.ChatMessage;
import com.dalai.llama.chat.domain.entity.ChatSession;
import com.dalai.llama.chat.domain.entity.EmbeddedDocument;
import com.dalai.llama.chat.dto.ChatMessageView;
import com.dalai.llama.chat.dto.SendChatMessageRequest;
import com.dalai.llama.chat.repository.ChatMessageRepository;
import com.dalai.llama.chat.service.action.ActionExecutionResult;
import com.dalai.llama.chat.service.action.ChatActionExecutor;
import com.dalai.llama.chat.service.generation.JsonExtraction;
import com.dalai.llama.chat.service.llmgateway.LlmGatewayChatRequest;
import com.dalai.llama.chat.service.llmgateway.LlmGatewayChatRequest.LlmGatewayMessage;
import com.dalai.llama.chat.service.llmgateway.LlmGatewayChatResponse;
import com.dalai.llama.chat.service.llmgateway.LlmGatewayClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The chat turn itself: persist the user's message, retrieve relevant context from the central
 * embedding index, ask the model for either a reply or a proposed action, and -- only for a
 * recognized action -- execute it through the matching {@link ChatActionExecutor} and report back
 * what happened. One LLM call per turn, no retry loop: an action the model got wrong (unknown
 * action name, missing required parameter) is reported to the user as a normal assistant message,
 * not retried automatically -- same "escalate, don't loop" discipline as critic-service's harness.
 */
@Service
public class ChatOrchestrator {

    private static final String TASK_KEY = "CHAT_WITH_ACTIONS";
    private static final int RETRIEVAL_LIMIT = 5;

    private final ChatSessionService chatSessionService;
    private final ChatMessageRepository chatMessageRepository;
    private final EmbeddedDocumentService embeddedDocumentService;
    private final List<ChatActionExecutor> actionExecutors;
    private final LlmGatewayClient llmGatewayClient;
    private final ObjectMapper objectMapper;
    private final String defaultModel;

    public ChatOrchestrator(
            ChatSessionService chatSessionService,
            ChatMessageRepository chatMessageRepository,
            EmbeddedDocumentService embeddedDocumentService,
            List<ChatActionExecutor> actionExecutors,
            LlmGatewayClient llmGatewayClient,
            ObjectMapper objectMapper,
            @Value("${chat.llm-gateway.default-text-model}") String defaultModel
    ) {
        this.chatSessionService = chatSessionService;
        this.chatMessageRepository = chatMessageRepository;
        this.embeddedDocumentService = embeddedDocumentService;
        this.actionExecutors = actionExecutors;
        this.llmGatewayClient = llmGatewayClient;
        this.objectMapper = objectMapper;
        this.defaultModel = defaultModel;
    }

    @Transactional
    public ChatMessageView sendMessage(UUID tenantId, UUID sessionId, SendChatMessageRequest request) {
        ChatSession session = chatSessionService.require(tenantId, sessionId);

        OffsetDateTime now = OffsetDateTime.now();
        chatMessageRepository.save(ChatMessage.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .role(ChatRole.USER)
                .content(request.content())
                .createdAt(now)
                .build());

        List<ChatMessage> history = chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
        List<EmbeddedDocument> retrieved = embeddedDocumentService.findSimilar(tenantId, session.getScopeId(), request.content(), RETRIEVAL_LIMIT);

        LlmGatewayChatResponse response = llmGatewayClient.chat(
                tenantId.toString(),
                "chat-" + sessionId + "-" + UUID.randomUUID(),
                new LlmGatewayChatRequest(defaultModel, List.of(new LlmGatewayMessage("user", "")),
                        JsonExtraction.JSON_MODE_PARAMS, TASK_KEY,
                        Map.of(
                                "conversationHistory", conversationHistory(history),
                                "retrievedContext", retrievedContext(retrieved),
                                "availableActions", ActionCatalog.describe(session.getScopeType())
                        )));

        ChatCompletionContent completion = parse(response);
        ChatMessage assistantMessage = completion.isAction()
                ? handleAction(tenantId, session, completion)
                : handleReply(sessionId, tenantId, completion, response);

        chatSessionService.touch(session);
        return toView(assistantMessage);
    }

    @Transactional(readOnly = true)
    public List<ChatMessageView> history(UUID tenantId, UUID sessionId) {
        chatSessionService.require(tenantId, sessionId);
        return chatMessageRepository.findBySessionIdOrderByCreatedAtAsc(sessionId).stream()
                .map(this::toView)
                .collect(Collectors.toList());
    }

    private ChatMessage handleAction(UUID tenantId, ChatSession session, ChatCompletionContent completion) {
        ChatActionType actionType = ChatActionTypeParser.parse(completion.action());
        ActionExecutionResult result = actionType == null
                ? ActionExecutionResult.failure("I tried to take an action (\"" + completion.action() + "\") that isn't one I actually support.")
                : executorFor(actionType).execute(tenantId, session, completion.actionParameters());

        return chatMessageRepository.save(ChatMessage.builder()
                .sessionId(session.getId())
                .tenantId(tenantId)
                .role(ChatRole.ASSISTANT)
                .content(result.summary())
                .actionType(actionType)
                .actionStatus(result.success() ? ChatActionStatus.EXECUTED : ChatActionStatus.FAILED)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    private ChatMessage handleReply(UUID sessionId, UUID tenantId, ChatCompletionContent completion, LlmGatewayChatResponse rawResponse) {
        String content = completion.replyContent() != null && !completion.replyContent().isBlank()
                ? completion.replyContent()
                : rawResponse.response();
        return chatMessageRepository.save(ChatMessage.builder()
                .sessionId(sessionId)
                .tenantId(tenantId)
                .role(ChatRole.ASSISTANT)
                .content(content)
                .createdAt(OffsetDateTime.now())
                .build());
    }

    private ChatActionExecutor executorFor(ChatActionType actionType) {
        return actionExecutors.stream()
                .filter(e -> e.actionType() == actionType)
                .findFirst()
                .orElseThrow(() -> ChatException.upstream("No executor registered for action " + actionType));
    }

    private ChatCompletionContent parse(LlmGatewayChatResponse response) {
        if (response == null || response.response() == null || response.response().isBlank()) {
            throw ChatException.upstream("llm-gateway returned no content for " + TASK_KEY);
        }
        try {
            return objectMapper.readValue(JsonExtraction.stripCodeFence(response.response()), ChatCompletionContent.class);
        } catch (Exception ex) {
            // A malformed JSON response is treated as plain conversational text rather than a
            // hard failure -- the chat should degrade to "just talk", not error out on the user.
            return new ChatCompletionContent("reply", response.response(), null, null);
        }
    }

    private String conversationHistory(List<ChatMessage> messages) {
        if (messages == null || messages.isEmpty()) {
            return "(no messages yet)";
        }
        return messages.stream().map(m -> m.getRole() + ": " + m.getContent())
                .collect(Collectors.joining("\n"));
    }

    private String retrievedContext(List<EmbeddedDocument> documents) {
        if (documents == null || documents.isEmpty()) {
            return "(nothing relevant found in this project's history)";
        }
        return documents.stream()
                .map(d -> "[" + d.getSourceService() + "/" + d.getKind() + "] " + d.getContent())
                .collect(Collectors.joining("\n\n"));
    }

    private ChatMessageView toView(ChatMessage message) {
        return new ChatMessageView(message.getRole(), message.getContent(), message.getActionType(),
                message.getActionStatus(), message.getCreatedAt());
    }
}
