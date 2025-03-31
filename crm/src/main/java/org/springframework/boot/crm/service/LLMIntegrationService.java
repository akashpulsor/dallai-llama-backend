package org.springframework.boot.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;


import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Service to integrate with LLM (OpenAI) for generating dynamic browser actions
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class LLMIntegrationService {
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    @Value("${openai.api.url}")
    private String openaiApiUrl;


    @Value("${openai.api.model}")
    private String openaiModel;



    /**
     * Generates JavaScript code for browser action based on current DOM and intent
     */
    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String generateActionScript(LlmData llmData, String portalContext,
                                       String domSnapshot,
                                       String intentDescription) {
        try {

            Map<String, Object> request = buildLLMRequest( portalContext, domSnapshot, intentDescription);

            return callLlm(llmData, request);
        } catch (Exception e) {
            log.error("Error generating action script with LLM", e);
            throw new RuntimeException("LLM script generation failed", e);
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String generateDomDescription(LlmData llmData,
                                       String domSnapshot,String intentDescription, String portalDescription) {
        try {
            Map<String, Object> request = buildLLMRequestToGenerateDomDescription( domSnapshot, intentDescription, portalDescription);
            return callLlm(llmData, request);
        } catch (Exception e) {
            log.error("Error generating action script with LLM", e);
            throw new RuntimeException("LLM script generation failed", e);
        }
    }

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 1000, multiplier = 2))
    public String generatePortalDescription(LlmData llmData,
                                         String url) {
        try {
            Map<String, Object> request = buildLLMRequestToGenerateDomDescription( url);
            return callLlm(llmData, request);
        } catch (Exception e) {
            log.error("Error generating action script with LLM", e);
            throw new RuntimeException("LLM script generation failed", e);
        }
    }

    public String callLlm(LlmData llmData, Map<String, Object> request) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + llmData.getApiKey());

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(request, headers);

        log.debug("Sending request to OpenAI API: {}", request);

        ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                openaiApiUrl, entity, JsonNode.class);

        if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
            return extractScriptFromResponse(response.getBody());
        } else {
            log.error("Failed to get response from OpenAI API: {}", response);
            throw new RuntimeException("Failed to generate script using LLM");
        }
    }

    private Map<String, Object> buildLLMRequestToGenerateDomDescription(
            String portalUrl) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", openaiModel);


        StringBuilder prompt = new StringBuilder();
        prompt.append("You surfing web, suddenly you saw a url, your job is to generate the description To do that you need to analyse the dom and generate description based on the content of dom, example of description is  `This page looks is from naukri.com login page, to proceed further we need to login` \n\n");
        prompt.append("Portal Url: ").append(portalUrl).append("\n");
        prompt.append("Return JSON with field domDescription:`Description of page that you have figured out`. The code should:\n");
        prompt.append("Example for response: \n");
        prompt.append("{domDescription: This page is from website naukri.com, this is home page of login, to proceed further you need to click on login }\n");
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt.toString());

        request.put("messages", new Object[]{message});
        request.put("temperature", 0.3);
        request.put("max_tokens", 2048);

        return request;
    }
    private Map<String, Object> buildLLMRequestToGenerateDomDescription(
            String domSnapshot,String intentDescription, String portalDescription) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", openaiModel);

        // Extract important DOM elements to reduce token count
        String simplifiedDom = simplifyDom(domSnapshot);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a browser automation expert. To do that you need to analyse the dom and generate description based on the content of dom, example of description is  `This page looks is from naukri.com login page, to proceed further we need to login` \n\n");
        prompt.append("DomSnapShot: ").append(domSnapshot).append("\n");
        prompt.append("Portal Context: ").append(portalDescription).append("\n");
        prompt.append("Intent: ").append(intentDescription).append("\n\n");
        prompt.append("Current DOM Structure:\n").append(simplifiedDom).append("\n\n");
        prompt.append("Return JSON with field domDescription:`Description of page that you have figured out`, actionData: `only the JavaScript code without any explanations`. The code should:\n");
        prompt.append("Example for Dom description: \n");
        prompt.append("{domDescription: This page is from website naukri.com, this is home page of login, to proceed further you need to click on login }\n");
        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt.toString());

        request.put("messages", new Object[]{message});
        request.put("temperature", 0.3);
        request.put("max_tokens", 2048);

        return request;
    }


    /**
     * Builds the request payload for the LLM API
     */
    private Map<String, Object> buildLLMRequest(
                                                String domSnapshot, String portalContext,
                                                String intentDescription) {
        Map<String, Object> request = new HashMap<>();
        request.put("model", openaiModel);

        // Extract important DOM elements to reduce token count
        String simplifiedDom = simplifyDom(domSnapshot);

        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a browser automation expert. To do that you need to look at dom and figure out what action you need to take, based on your analysis please Generate JavaScript code to perform action, you can take of DOM from Portal Context, Reason of opening this page from `intent:`, Based on intent analyze the current dom and think what you should do to complete that request so example of actions that you may want to do is login, comment, like share, analyze dom data for shortlisting\n\n");
        prompt.append("Portal Context: ").append(portalContext).append("\n");
        prompt.append("Intent: ").append(intentDescription).append("\n\n");
        prompt.append("Current DOM Structure:\n").append(simplifiedDom).append("\n\n");
        prompt.append("Return JSON with field actionType:`action which need to perform example is click, scroll, fill text box and click login`, actionData: `only the JavaScript code without any explanations`. The code should:\n");
        prompt.append("Return only the JavaScript code without any explanations. The code should:\n");
        prompt.append("1. Use CSS or XPath selectors to locate elements\n");
        prompt.append("2. This function will be used in puppeteer for browser automation\n");
        prompt.append("3. Perform the requested action\n");
        prompt.append("4. Handle potential errors gracefully\n");
        prompt.append("5. Return results in a context object if needed\n\n");

        prompt.append("Example for clicking a button: \n");
        prompt.append("function performAction() {\n");
        prompt.append("  try {\n");
        prompt.append("    const button = document.querySelector('#submit-btn') || document.evaluate(\"//button[contains(text(), 'Submit')]\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue;\n");
        prompt.append("    if (button) {\n");
        prompt.append("      button.click();\n");
        prompt.append("      return { success: true, message: 'Button clicked successfully' };\n");
        prompt.append("    } else {\n");
        prompt.append("      return { success: false, message: 'Button not found' };\n");
        prompt.append("    }\n");
        prompt.append("  } catch (error) {\n");
        prompt.append("    return { success: false, message: `Error: ${error.message}` };\n");
        prompt.append("  }\n");
        prompt.append("}\n\n");
        prompt.append("performAction();\n");

        Map<String, Object> message = new HashMap<>();
        message.put("role", "user");
        message.put("content", prompt.toString());

        request.put("messages", new Object[]{message});
        request.put("temperature", 0.3);
        request.put("max_tokens", 2048);

        return request;
    }

    /**
     * Simplifies the DOM to reduce token count while preserving important elements
     */
    private String simplifyDom(String domSnapshot) {
        try {
            JsonNode dom = objectMapper.readTree(domSnapshot);
            // In a real implementation, this would extract important elements based on
            // the current action type and intent. For now, we'll just trim it to save tokens.
            return objectMapper.writeValueAsString(dom).substring(0, Math.min(10000, domSnapshot.length()));
        } catch (Exception e) {
            log.warn("Failed to simplify DOM, using truncated version", e);
            return domSnapshot.substring(0, Math.min(10000, domSnapshot.length()));
        }
    }

    /**
     * Extracts the generated script from the LLM response
     */
    private String extractScriptFromResponse(JsonNode response) {
        try {
            String content = response.path("choices").path(0).path("message").path("content").asText();

            // Extract code blocks if present
            if (content.contains("```javascript")) {
                int start = content.indexOf("```javascript") + "```javascript".length();
                int end = content.indexOf("```", start);
                return content.substring(start, end).trim();
            } else if (content.contains("```js")) {
                int start = content.indexOf("```js") + "```js".length();
                int end = content.indexOf("```", start);
                return content.substring(start, end).trim();
            } else if (content.contains("```")) {
                int start = content.indexOf("```") + "```".length();
                int end = content.indexOf("```", start);
                return content.substring(start, end).trim();
            }

            // If no code blocks, assume the entire content is code
            return content.trim();
        } catch (Exception e) {
            log.error("Failed to extract script from response", e);
            throw new RuntimeException("Could not parse LLM response", e);
        }
    }

    /**
     * Analyzes DOM to extract relevant information for decision making
     */


    /**
     * Extracts JSON data from LLM response
     */
    private Map<String, Object> extractJsonFromResponse(JsonNode response) {
        try {
            String content = response.path("choices").path(0).path("message").path("content").asText();

            // Extract JSON blocks if present
            if (content.contains("```json")) {
                int start = content.indexOf("```json") + "```json".length();
                int end = content.indexOf("```", start);
                String jsonString = content.substring(start, end).trim();
                return objectMapper.readValue(jsonString, Map.class);
            } else if (content.contains("```")) {
                int start = content.indexOf("```") + "```".length();
                int end = content.indexOf("```", start);
                String jsonString = content.substring(start, end).trim();
                return objectMapper.readValue(jsonString, Map.class);
            }

            // Try to parse the entire content as JSON
            return objectMapper.readValue(content, Map.class);
        } catch (Exception e) {
            log.error("Failed to extract JSON from response", e);
            throw new RuntimeException("Could not parse LLM analysis response", e);
        }
    }
}
