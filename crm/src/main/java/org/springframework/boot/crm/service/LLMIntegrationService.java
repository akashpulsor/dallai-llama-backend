package org.springframework.boot.crm.service;


import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.Data;
import org.springframework.boot.crm.dto.BrowserDataDto;
import org.springframework.boot.crm.dto.ChatMessage;
import org.springframework.boot.crm.dto.LLMResponseDto;
import org.springframework.boot.crm.entity.CampaignData;
import org.springframework.boot.crm.entity.Intent;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.PortalConfiguration;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.*;
import java.util.stream.Collectors;


@Data
public class LLMIntegrationService {


    private  final int MAX_CHUNK_SIZE = 128000; // Adjust based on model's context window
    private  final String OPENAI_API_KEY ;
    private  final String OPENAI_API_URL;
    private  final String OPENAI_MODEL; // Use appropriate model
    private  final ObjectMapper objectMapper = new ObjectMapper();
    private  final HttpClient httpClient = HttpClient.newHttpClient();


    // Helper interfaces for functional parameters
    private interface TriFunction<T, U, V, R> {
        R apply(T t, U u, V v);
    }

    private interface QuadFunction<T, U, V, W> {
        W apply(T t, U u, V v);
    }

    private final PortalConfiguration portalConfiguration;
    private  final  LlmData llmData ;
    private  final CampaignData campaignData;

    private  final  String sessionId;

    private final int businessId;
    private final String  browserRunId;
    private final String  browserSessionId;

    private final Intent rootIntent;

    private final Queue<Intent> intentQueue ;

    public LLMIntegrationService(PortalConfiguration portalConfiguration,
                                 LlmData llmData,
                                 CampaignData campaignData,
                                 String sessionId,
                                 int businessId,
                                 String  browserRunId,
                                 String  browserSessionId){
        this.portalConfiguration = portalConfiguration;
        this.llmData = llmData;
        this.campaignData = campaignData;
        this.sessionId = sessionId;
        this.businessId = businessId;
        this.browserRunId = browserRunId;
        this.browserSessionId = browserSessionId;
        this.rootIntent = portalConfiguration.getIntents().get(0);
        this.intentQueue = new LinkedList<>();
        this.OPENAI_API_KEY = llmData.getApiKey();
        this.OPENAI_API_URL = llmData.getModelUrl();
        this.OPENAI_MODEL = llmData.getModelName();
        for(int i =1; i < portalConfiguration.getIntents().size(); i++ ){
            this.intentQueue.offer(portalConfiguration.getIntents().get(i));
        }
    }


    public  String processMetaDataWithOpenAI(BrowserDataDto.MetaData metaData, String userIntent, String currentStep) throws Exception {
        List<String> chunks = chunkMetaData(metaData);
        String response = sendOpenAIRequest(createInitialMessages(userIntent, currentStep, chunks, 0));
        for (int i = 1; i < chunks.size(); i++) {
            response = sendOpenAIRequest(updateMessages(createInitialMessages(userIntent, currentStep, chunks, i), response));
        }
        return sendOpenAIRequest(finalizeMessages(createInitialMessages(userIntent, currentStep, chunks, chunks.size() - 1), response, currentStep));
    }

    private  List<ChatMessage> createInitialMessages(String userIntent, String currentStep, List<String> chunks, int index) {
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(new ChatMessage("system", "You are an expert web automation assistant..."));
        String content = "Analyze... My global intent: " + this.rootIntent.getDescription() + ". Current step: " + currentStep + ".\n\n";
        content += "This is chunk " + (index + 1) + " of " + chunks.size() + ": " + chunks.get(index);
        messages.add(new ChatMessage("user", content));
        return messages;
    }

    private  List<ChatMessage> updateMessages(List<ChatMessage> existingMessages, String assistantResponse) {
        existingMessages.add(new ChatMessage("assistant", assistantResponse));
        existingMessages.add(new ChatMessage("user", "Please update your Puppeteer script based on this additional information."));
        return existingMessages;
    }


    private List<ChatMessage> finalizeMessages(List<ChatMessage> existingMessages, String assistantResponse, String currentStep) {
        existingMessages.add(new ChatMessage("assistant", assistantResponse));
        existingMessages.add(new ChatMessage("user", "Now that you've analyzed all chunks, please provide the final Puppeteer script... " + currentStep));
        existingMessages.add(new ChatMessage("user", "your ultimate goal is - " + this.rootIntent.getDescription()));
        existingMessages.add(new ChatMessage("user", "your current goal is - " + currentStep));
        existingMessages.add(new ChatMessage("user", "The response should be in json format:\n" +
                "```json\n" +
                "{\n" +
                "  \"type\": \"act|extract|analyse\",\n" +
                "  \"data\": [\n" +
                "    {\n" +
                "      \"actionData\": \"Puppeteer script for the action (e.g., await page.click('#submit-button');)\",\n" +
                "      \"description\": \"Reason for choosing this action and the element. Be specific and explain the logic.\",\n" +
                "      \"targetElement\": \"CSS selector or XPath of the element being interacted with (if applicable)\"\n" +
                "    }\n" +
                "    // ... (more actions if needed for this step)\n" +
                "  ],\n" +
                "  \"reason\": \"Overall reasoning for the chosen action(s) and the determined 'type'.\"\n" +
                "}\n" +
                "```"));
        existingMessages.add(new ChatMessage("user", "Instructions and Constraints:\n" +
                "1. **Type Determination:**\n" +
                "   - `\"act\"`: When you are confident you can perform a direct action on the current page to progress towards the current goal. Provide the exact Puppeteer script in `actionData`.\n" +
                "   - `\"extract\"`: When you need to extract information from the current page to make a decision or fulfill the ultimate goal. The `data` field should describe what information needs to be extracted and how (e.g., using `page.$eval` or `page.$$eval`). The `actionData` can be a description of the extraction logic.\n" +
                "   - `\"analyse\"`: When you need more information or context to determine the next action. This should ideally be used sparingly. The `data` field should describe what information you need or what analysis you are performing (though ideally, you should aim for `act` or `extract`).\n" +
                "2. **Action Generation:**\n" +
                "   - Be precise and provide the exact Puppeteer script within the `actionData` field for `\"act\"` type.\n" +
                "   - When selecting elements, prioritize robust selectors (e.g., using unique attributes, IDs, or well-defined CSS). Avoid relying solely on text content if it's likely to change.\n" +
                "   - Include a detailed `description` explaining *why* you chose that specific action and element. Justify your reasoning based on the context and your understanding of web pages.\n" +
                "   - If an action targets a specific element, always include the `targetElement` selector.\n" +
                "3. **Confidence Level:** You are asked to provide actions you feel **highly confident** will work. If you are uncertain or need more information, lean towards `\"analyse\"` or describe your uncertainty in the `reason` field. Avoid making assumptions.\n" +
                "4. **Context Utilization:** Carefully consider the `Ultimate Goal`, `Current Goal`, `Portal Configuration`, and `Campaign Data` when determining your actions. You can use the information in these fields to make informed decisions (e.g., filling in form fields).\n" +
                "5. **Step-by-Step Reasoning:** Break down the `currentStep` into smaller, actionable steps if necessary. The `data` array can contain multiple actions if they logically follow each other to complete the `currentStep`.\n" +
                "6. **Avoid Ambiguity:** Be explicit in your instructions and the generated Puppeteer code.\n" +
                "7. **JSON Adherence:** Your response **must always** be a valid JSON object conforming to the specified format."));
        existingMessages.add(new ChatMessage("user", "In case you want to have some context regarding portal, user name, password use portal configuration:" + this.portalConfiguration.toString()));
        existingMessages.add(new ChatMessage("user", "In case you want to have some context for which campaign this is running use this data" + this.campaignData.toString()));
        existingMessages.add(new ChatMessage("user", "You can pick values to fill in various text box from the context provide"));
        return existingMessages;
    }

    public  String extractLLMResponseDto(String openAiResponse) {
        int startIndex = openAiResponse.indexOf("```json");
        if (startIndex == -1) {
            startIndex = openAiResponse.indexOf("{");
            if (startIndex == -1) {
                System.out.println("No JSON start marker found.");
                return null;
            }
        } else {
            startIndex += 7; // Move past "```json"
        }

        int endIndex = openAiResponse.indexOf("```", startIndex);
        if (endIndex == -1) {
            endIndex = openAiResponse.lastIndexOf("}");
            if (endIndex == -1) {
                System.out.println("No JSON end marker found.");
                return null;
            } else {
                endIndex++; // Include the last curly brace
            }
        }

        return openAiResponse.substring(startIndex, endIndex).trim();


    }

    private  String sendOpenAIRequest(List<ChatMessage> messages) throws IOException, InterruptedException {
        ObjectNode req = objectMapper.createObjectNode().put("model", OPENAI_MODEL).set("messages", objectMapper.valueToTree(messages));
        HttpRequest request = HttpRequest.newBuilder(URI.create(OPENAI_API_URL+"/chat/completions")).header("Content-Type", "application/json").header("Authorization", "Bearer " + OPENAI_API_KEY).POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(req))).build();
        HttpResponse<String> res = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        return res.statusCode() >= 200 && res.statusCode() < 300 && objectMapper.readTree(res.body()).has("choices") ? objectMapper.readTree(res.body()).get("choices").get(0).get("message").get("content").asText() : null;
    }

    private  List<String> chunkMetaData(BrowserDataDto.MetaData metaData) throws Exception {
        List<String> chunks = new ArrayList<>();
        if (metaData.getClickableElementsInfo() != null) chunks.addAll(splitStringIntoChunks(metaData.getClickableElementsInfo(), "clickableElementsInfo", LLMIntegrationService::findChunkBoundary));
        if (metaData.getFormElementsInfo() != null) chunks.addAll(splitStringIntoChunks(metaData.getFormElementsInfo(), "formElementsInfo", LLMIntegrationService::findChunkBoundary));
        if (metaData.getDomSnapshot() != null) chunks.addAll(splitStringIntoChunks(metaData.getDomSnapshot(), "domSnapshotPart", LLMIntegrationService::findChunkBoundary));
        if (metaData.getScreenshotBase64() != null) chunks.addAll(splitStringIntoChunks(metaData.getScreenshotBase64(), "screenshotBase64Part", (s, start, max) -> Math.min(start + max, s.length())));
        return chunks;
    }

    private  List<String> splitStringIntoChunks(String text, String contentKey, QuadFunction<String, Integer, Integer, Integer> boundaryFunction) throws IOException {
        List<String> chunks = new ArrayList<>();
        for (int i = 0; i < text.length(); i += MAX_CHUNK_SIZE) {
            int end = boundaryFunction.apply(text, i, MAX_CHUNK_SIZE);
            ObjectNode node = objectMapper.createObjectNode().put(contentKey, text.substring(i, end));
            chunks.add(objectMapper.writeValueAsString(node));
        }
        return chunks;
    }

    private static int findChunkBoundary(String text, int start, int maxSize) {
        int end = Math.min(start + maxSize, text.length());
        int lastClosingTag = text.lastIndexOf("</", end);
        if (lastClosingTag > start && lastClosingTag > end - 200) {
            int closingBracket = text.indexOf(">", lastClosingTag);
            if (closingBracket > lastClosingTag && closingBracket < end + 100) return closingBracket + 1;
        }
        return end;
    }
}