package com.dalai.llama.product.service.epsilon;

import com.dalai.llama.product.dto.request.SearchAvailableDidsRequest;
import com.dalai.llama.product.dto.response.AvailableDidResponse;
import com.dalai.llama.product.service.DidProvisioningService;
import com.dalai.llama.product.service.epsilon.dto.EpsilonVoiceProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class EpsilonProvisioningService implements DidProvisioningService {

    private final EpsilonApiService apiService;

    private final EpsilonVoiceProperties epsilonVoiceProperties;

    @Override
    public List<AvailableDidResponse> searchAvailableDids(SearchAvailableDidsRequest request) {

        var response = apiService.getDidList();

        if (response == null || response.getData() == null) {
            return List.of();
        }

        var defaults = epsilonVoiceProperties.getDefaults();

        String prefix = request.getPrefix();
        int limit = request.getLimit() > 0 ? request.getLimit() : Integer.MAX_VALUE;

        return response.getData()
                .stream()
                // Optional prefix filtering
                .filter(d -> prefix == null
                        || String.valueOf(d.getDidNumber()).startsWith(prefix))
                // Apply limit safely
                .limit(limit)
                .map(d -> AvailableDidResponse.builder()
                        .number(String.valueOf(d.getDidNumber()))
                        .country(request.getCountry())
                        .city(defaults.getCity())
                        .type(defaults.getType())
                        .monthlyFee(defaults.getMonthlyFee())
                        .setupFee(defaults.getSetupFee())
                        .inboundPrice(defaults.getInboundPrice())
                        .outboundPrice(defaults.getOutboundPrice())
                        .currency(defaults.getCurrency())
                        .provider(providerName())
                        .build())
                .toList();
    }

    /**
     * Epsilon currently does not support order via API (if not available)
     */
    @Override
    public String orderDid(String number) {
        throw new UnsupportedOperationException("Epsilon order not supported yet");
    }

    @Override
    public String configureSip(String name, String host, int port) {
        throw new UnsupportedOperationException("Epsilon SIP config not supported");
    }

    @Override
    public String createTrunk(String name, String sipConfigId) {
        throw new UnsupportedOperationException("Epsilon trunk creation not supported");
    }

    @Override
    public boolean supports(String country) {
        if (country == null || country.isBlank()) {
            return true; // allow global search
        }
        // Epsilon currently supports India numbers
        return "IN".equalsIgnoreCase(country);
    }

    @Override
    public String providerName() {
        return "EPSILON";
    }

    @Override
    public boolean isHealthy() {
        return apiService.isHealthy();
    }
}
