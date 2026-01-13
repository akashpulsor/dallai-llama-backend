package com.dalai.llama.product.service.didww;

import com.dalai.llama.product.service.didww.dto.DidwwAvailableDidResponse;
import com.dalai.llama.product.service.didww.dto.DidwwOrderRequest;
import com.dalai.llama.product.service.didww.dto.DidwwSipConfigRequest;
import com.dalai.llama.product.service.didww.dto.DidwwTrunkRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DidwwProvisioningService {

    private final DidwwApiService apiService;

    /**
     * STEP 1: Search available DIDs
     */
    public DidwwAvailableDidResponse searchAvailableDids(
            String country,
            String city,
            String prefix,
            String type,
            int limit
    ) {
        String query = String.format(
                "country=%s&city=%s&prefix=%s&type=%s&limit=%d",
                country, city, prefix, type, limit
        );
        return apiService.searchAvailableDids(query);
    }

    /**
     * STEP 2: Order DID
     */
    public String orderDid(String didNumber) {
        DidwwOrderRequest request = DidwwOrderRequest.builder()
                .items(List.of(
                        DidwwOrderRequest.OrderItem.builder()
                                .did(didNumber)
                                .quantity(1)
                                .build()
                ))
                .build();

        return apiService.orderDid(request);
    }

    /**
     * STEP 3: Configure SIP
     */
    public String configureSip(String name, String host, int port) {
        DidwwSipConfigRequest request = DidwwSipConfigRequest.builder()
                .name(name)
                .host(host)
                .port(port)
                .transport("UDP")
                .build();

        return apiService.createSipConfig(request);
    }

    /**
     * STEP 4: Create trunk
     */
    public String createTrunk(String name, String sipConfigId) {
        DidwwTrunkRequest request = DidwwTrunkRequest.builder()
                .name(name)
                .sipConfigId(sipConfigId)
                .cliValidation(true)
                .build();

        return apiService.createTrunk(request);
    }
}
