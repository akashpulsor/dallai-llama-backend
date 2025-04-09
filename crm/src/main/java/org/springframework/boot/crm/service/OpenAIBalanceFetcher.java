package org.springframework.boot.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.crm.entity.LlmData;

import java.io.IOException;
import java.time.LocalDate;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;


public class OpenAIBalanceFetcher implements  BalanceFetcher{

    private final String API_KEY; // Replace with your actual API key
    private final String BASE_URL;
    private final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private final ObjectMapper objectMapper;

    private final LlmData llmData;

    public OpenAIBalanceFetcher(LlmData llmData){
        this.llmData = llmData;
        this.API_KEY = llmData.getApiKey();
        this.BASE_URL = "https://api.openai.com/dashboard/billing";
        this.objectMapper = new ObjectMapper();
    }
    public  String fetchUsageData(LocalDate startDate, LocalDate endDate) throws IOException, InterruptedException {
        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(this.BASE_URL + "/usage?start_date=" + startDate.format(DATE_FORMATTER) +
                        "&end_date=" + endDate.format(DATE_FORMATTER)))
                .header("Authorization", "Bearer " + API_KEY)
                .header("Content-Type", "application/json")
                .GET()
                .build();

        HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            return response.body();
        } else {
            throw new IOException("Failed to fetch usage data. Status code: " + response.statusCode() +
                    ", Response: " + response.body());
        }
    }

    public  double calculateTotalUsage(LocalDate startDate) throws IOException, InterruptedException {
        LocalDate today = LocalDate.now();
        double totalCost = 0;
        LocalDate currentDate = startDate;

        while (!currentDate.isAfter(today)) {
            String responseBody = fetchUsageData(currentDate, currentDate);
            JsonNode jsonResponse = this.objectMapper.readTree(responseBody);
            JsonNode dailyCosts = jsonResponse.get("daily_costs");

            if (dailyCosts != null && dailyCosts.isArray()) {
                for (JsonNode dailyCostNode : dailyCosts) {
                    JsonNode costNode = dailyCostNode.get("cost");
                    if (costNode != null && costNode.isNumber()) {
                        totalCost += costNode.asDouble();
                    }
                }
            }
            currentDate = currentDate.plusDays(1);
        }
        return totalCost;
    }

}
