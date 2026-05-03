package com.dalai.llama.product.service.impl;


import com.dalai.llama.product.dto.request.SearchAvailableDidsRequest;
import com.dalai.llama.product.dto.response.AvailableDidResponse;
import com.dalai.llama.product.repository.DidRepository;
import com.dalai.llama.product.service.DidProvisioningOrchestrator;
import com.dalai.llama.product.service.DidProvisioningService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class DidProvisioningOrchestratorImpl implements DidProvisioningOrchestrator {

    private final List<DidProvisioningService> providers;
    private final DidRepository didRepository;

    @Override
    public List<AvailableDidResponse> search(SearchAvailableDidsRequest request) {

        List<AvailableDidResponse> providerResults = providers.stream()
                .filter(p -> p.supports(request.getCountry()))
                .filter(DidProvisioningService::isHealthy)
                .flatMap(p -> p.searchAvailableDids(request).stream())
                .toList();

        // Filter out DIDs that are already reserved/in-use in our system
        Set<String> reservedNumbers = didRepository.findAllReservedNumbers();

        if (reservedNumbers.isEmpty()) {
            return providerResults;
        }

        log.debug("Filtering out {} reserved DIDs from {} provider results",
                reservedNumbers.size(), providerResults.size());

        return providerResults.stream()
                .filter(did -> !reservedNumbers.contains(did.getNumber()))
                .toList();
    }

    @Override
    public DidProvisioningService resolveProvider(String country) {

        return providers.stream()
                .filter(p -> p.supports(country))
                .filter(DidProvisioningService::isHealthy)
                .findFirst()
                .orElseThrow(() ->
                        new RuntimeException("No healthy provider found for country: " + country));
    }

    /**
     * Paginated + Sorted Search
     */
    public Page<AvailableDidResponse> searchPaginated(SearchAvailableDidsRequest request,
                                                      int page,
                                                      int size,
                                                      Sort sort) {

        List<AvailableDidResponse> allResults = search(request);

        // Sorting (future recommendation hook)
        if (sort.isSorted()) {
            allResults = allResults.stream()
                    .sorted(getComparator(sort))
                    .toList();
        }

        int start = Math.min(page * size, allResults.size());
        int end = Math.min(start + size, allResults.size());

        List<AvailableDidResponse> content =
                start >= end ? List.of() : allResults.subList(start, end);

        return new PageImpl<>(content, PageRequest.of(page, size, sort), allResults.size());
    }

    private Comparator<AvailableDidResponse> getComparator(Sort sort) {

        return sort.stream()
                .map(order -> {
                    Comparator<AvailableDidResponse> comparator;

                    switch (order.getProperty()) {

                        case "monthlyFee":
                            comparator = Comparator.comparing(
                                    r -> {
                                        try {
                                            return r.getMonthlyFee() == null
                                                    ? 0.0
                                                    : Double.parseDouble(r.getMonthlyFee());
                                        } catch (Exception e) {
                                            return 0.0;
                                        }
                                    });
                            break;

                        case "number":
                            comparator = Comparator.comparing(AvailableDidResponse::getNumber);
                            break;

                        default:
                            comparator = Comparator.comparing(AvailableDidResponse::getNumber);
                    }

                    return order.isAscending()
                            ? comparator
                            : comparator.reversed();
                })
                .reduce(Comparator::thenComparing)
                .orElse(Comparator.comparing(AvailableDidResponse::getNumber));
    }
}
