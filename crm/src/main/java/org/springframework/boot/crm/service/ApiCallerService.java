package org.springframework.boot.crm.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

@Slf4j
@Service
public class ApiCallerService {

    @Autowired
    private RestTemplate restTemplate;

    @Autowired
    private ObjectMapper objectMapper; // For converting object to JSON string

    public ResponseEntity<String> callPostApiWithObject(String url, Object requestObject) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        try {
            // Convert the Java object to a JSON string using ObjectMapper
            String requestBody = objectMapper.writeValueAsString(requestObject);

            HttpEntity<String> requestEntity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<String> response = restTemplate.exchange(
                    url,
                    HttpMethod.POST,
                    requestEntity,
                    String.class // Or your expected response type
            );
            return response;
        } catch (Exception e) {
            log.error("Error calling POST API with object: " + e.getMessage());
            throw new RuntimeException("Error sending object as JSON", e); // Or handle more specifically
        }
    }

}
