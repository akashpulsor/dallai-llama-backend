package org.springframework.boot.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

@Slf4j
public class TranscriptionUtils {

    public static String transcribeAudio(byte[] audioData, LlmData llmData) {
        try {
            String WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions";
            // Download audio from Twilio URL

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Bearer " + llmData.getApiKey());
            // Create audio resource
            ByteArrayResource audioResource = new ByteArrayResource(audioData) {
                @Override
                public String getFilename() {
                    return "audio.mp3";
                }
            };
            // Prepare request body
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", "whisper-1");
            body.add("file", audioResource);
            // Create request entity
            HttpEntity<MultiValueMap<String, Object>> requestEntity =
                    new HttpEntity<>(body, headers);
            // Make API call
            ResponseEntity<String> response = new RestTemplate().exchange(
                    WHISPER_API_URL,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getBody());
                return root.get("text").asText();
            } else {
                throw new RuntimeException("Whisper API error: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Error transcribing audio: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to transcribe audio", e);
        }
    }

    public static byte[] downloadAudio(String url, TwilioData twilioData) {
        try {
            HttpHeaders headers = new HttpHeaders();
            String auth = twilioData.getAccountSid() + ":" + twilioData.getAccountAuthToken();
            String authHeader = "Basic " + java.util.Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.US_ASCII));
            headers.set("Authorization", authHeader);

            // Create HttpEntity with headers
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Use exchange method instead of getForEntity to include headers
            ResponseEntity<byte[]> response = new RestTemplate().exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    byte[].class
            );

            return response.getBody();
        } catch (Exception e) {
            log.error("Error downloading audio: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to download audio", e);
        }
    }
}
