package org.springframework.boot.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.speech.v1.*;
import com.google.protobuf.ByteString;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.util.Base64;

@Service
public class TranscriptionService {

    public  String transcribeBase64Audio(String base64Audio, LlmData llmData) throws JsonProcessingException {
        // Decode the Base64 string to binary
        String API_URL = "https://api.openai.com/v1/audio/transcriptions";
        byte[] audioBytes = Base64.getDecoder().decode(base64Audio);

        // Create RestTemplate instance
        RestTemplate restTemplate = new RestTemplate();

        // Set up headers
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + llmData.getApiKey());

        // Create a resource from the audio bytes
        ByteArrayResource audioResource = new ByteArrayResource(audioBytes) {
            @Override
            public String getFilename() {
                return "audio.mp3"; // Set appropriate filename and extension
            }
        };

        // Set up the request body
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("model", "whisper-1");
        body.add("file", audioResource);

        // Create the request entity
        HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);

        // Make the API call
        ResponseEntity<String> response = restTemplate.exchange(
                API_URL,
                HttpMethod.POST,
                requestEntity,
                String.class);

        // Parse the response
        if (response.getStatusCode() == HttpStatus.OK) {
            ObjectMapper mapper = new ObjectMapper();
            JsonNode root = mapper.readTree(response.getBody());
            return root.get("text").asText();
        } else {
            throw new RuntimeException("Error calling Whisper API: " + response.getStatusCode());
        }
    }
}