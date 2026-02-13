package com.dalai.llama.product.service.didww;

import com.dalai.llama.product.dto.request.SearchAvailableDidsRequest;
import com.dalai.llama.product.dto.response.AvailableDidResponse;
import com.dalai.llama.product.service.DidProvisioningService;
import com.dalai.llama.product.service.didww.dto.DidwwAvailableDidResponse;
import com.dalai.llama.product.service.didww.dto.DidwwOrderRequest;
import com.dalai.llama.product.service.didww.dto.DidwwSipConfigRequest;
import com.dalai.llama.product.service.didww.dto.DidwwTrunkRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class DidwwProvisioningService implements DidProvisioningService {

    private final DidwwApiService apiService;

    /**
     * STEP 1: Search available DIDs
     */
    @Override
    public List<AvailableDidResponse> searchAvailableDids(SearchAvailableDidsRequest request) {

        String query = String.format(
                "country=%s&city=%s&prefix=%s&type=%s&limit=%d",
                request.getCountry(),
                request.getCity(),
                request.getPrefix(),
                request.getType(),
                request.getLimit()
        );

        var response = new DidwwAvailableDidResponse();//apiService.searchAvailableDids(query);

        if (response == null || response.getData() == null) {
            return List.of();
        }

        return response.getData()
                .stream()
                .map(d -> AvailableDidResponse.builder()
                        .number(d.getNumber())
                        .country(d.getCountry())
                        .city(d.getCity())
                        .type(d.getType())
                        .monthlyFee(d.getMonthlyFee())
                        .setupFee(d.getSetupFee())
                        .provider(providerName())
                        .build())
                .toList();
    }

    /**
     * STEP 2: Order DID
     */
    @Override
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
    @Override
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
    @Override
    public String createTrunk(String name, String sipConfigId) {

        DidwwTrunkRequest request = DidwwTrunkRequest.builder()
                .name(name)
                .sipConfigId(sipConfigId)
                .cliValidation(true)
                .build();

        return apiService.createTrunk(request);
    }

    @Override
    public boolean supports(String country) {
        return List.of("US", "UK", "IN").contains(country.toUpperCase());
    }

    @Override
    public String providerName() {
        return "DIDWW";
    }

    @Override
    public boolean isHealthy() {
        return false;//apiService.isHealthy();
    }
}
