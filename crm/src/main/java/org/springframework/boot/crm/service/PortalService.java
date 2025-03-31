package org.springframework.boot.crm.service;

import jakarta.transaction.Transactional;
import org.springframework.boot.crm.dto.IntentDTO;
import org.springframework.boot.crm.dto.PortalConfigurationDTO;
import org.springframework.boot.crm.dto.PortalConfigurationResponse;
import org.springframework.boot.crm.entity.Intent;
import org.springframework.boot.crm.entity.PortalConfiguration;
import org.springframework.boot.crm.exceptions.EntityNotFoundException;
import org.springframework.boot.crm.repository.IntentRepository;
import org.springframework.boot.crm.repository.PortalConfigurationRepository;
import org.springframework.stereotype.Service;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PortalService {

    private final PortalConfigurationRepository portalConfigurationRepository;
    private final IntentRepository intentRepository;


    public PortalService(
            PortalConfigurationRepository portalConfigurationRepository,
            IntentRepository intentRepository
    ) {
        this.portalConfigurationRepository = portalConfigurationRepository;
        this.intentRepository = intentRepository;
    }

    // Validation Methods
    private void validatePortalConfiguration(PortalConfiguration portalConfiguration) {
        // Basic validation
        if (portalConfiguration == null) {
            throw new IllegalArgumentException("Portal configuration cannot be null");
        }

        // Validate required fields
        validateRequiredField(portalConfiguration.getPortalName(), "Portal Name");
        validateRequiredField(portalConfiguration.getBaseUrl(), "Base URL");
        validateRequiredField(portalConfiguration.getUserName(), "User Name");
        validateRequiredField(portalConfiguration.getPassword(), "Password");

        // Additional specific validations
        if (portalConfiguration.getBusinessId() <= 0) {
            throw new IllegalArgumentException("Business ID must be a positive number");
        }

        // URL validation
        validateUrl(portalConfiguration.getBaseUrl());

        // Validate intents if present
        if (portalConfiguration.getIntents() != null) {
            portalConfiguration.getIntents().forEach(intent -> validateIntent(intent, portalConfiguration));
        }
    }

    private void validateIntent(Intent intent, PortalConfiguration portalConfiguration) {
        // Validate required fields for intent
        validateRequiredField(intent.getIntentName(), "Intent Name");
        validateRequiredField(intent.getDescription(), "Intent Description");

        if (intent.getSequenceNumber() < 0) {
            throw new IllegalArgumentException("Sequence number must be non-negative");
        }

        // Set portal configuration for intent
        intent.setPortalConfiguration(portalConfiguration);

        // Validate and prepare child intents
        if (intent.getChildIntents() != null) {
            intent.getChildIntents().forEach(childIntent -> {
                validateIntent(childIntent, portalConfiguration);
                childIntent.setRootIntent(intent);
            });
        }
    }

    private void validateRequiredField(String field, String fieldName) {
        if (field == null || field.trim().isEmpty()) {
            throw new IllegalArgumentException(fieldName + " is required");
        }
    }

    private void validateUrl(String url) {
        try {
            new URL(url);
        } catch (MalformedURLException e) {
            throw new IllegalArgumentException("Invalid URL: " + url);
        }
    }

    // CRUD Operations
    @Transactional
    public PortalConfiguration getPortalConfigurationByName(String portalName) {
        return portalConfigurationRepository.findByPortalNameWithIntents(portalName)
                .orElseThrow(() -> new EntityNotFoundException("Portal configuration not found with name: " + portalName));
    }

    @Transactional
    public PortalConfiguration getPortalConfigurationById(Integer portalId) {
        return portalConfigurationRepository.findByIdWithIntents(portalId)
                .orElseThrow(() -> new EntityNotFoundException("Portal configuration not found with ID: " + portalId));
    }

    @Transactional
    public PortalConfigurationResponse createPortalConfiguration(PortalConfigurationDTO portalConfigurationDTO) {
        // Validate the portal configuration
        PortalConfiguration portalConfiguration = createPortalConfigurationEntity(portalConfigurationDTO);
        validatePortalConfiguration(portalConfiguration);
        // Ensure relationships are properly set
        portalConfiguration =portalConfigurationRepository.save(portalConfiguration);
        return createPortalConfigurationResponse(portalConfiguration);
    }

    public List<PortalConfigurationResponse> getPortalConfiguration(int businessId) {

        List<PortalConfiguration> portalConfigurationList = portalConfigurationRepository.findByBusinessId(businessId);

        for(PortalConfiguration portalConfiguration:portalConfigurationList){
            createPortalConfigurationResponse(portalConfiguration);
        }

        return  portalConfigurationList.stream().map(this::createPortalConfigurationResponse).collect(Collectors.toList());
    }

    private PortalConfiguration createPortalConfigurationEntity(PortalConfigurationDTO portalConfigurationDTO) {
        PortalConfiguration portalConfiguration = PortalConfiguration.builder().
                portalName(portalConfigurationDTO.getPortalName())
                .portalDescription(portalConfigurationDTO.getPortalDescription())
                .baseUrl(portalConfigurationDTO.getBaseUrl())
                .userName(portalConfigurationDTO.getUserName())
                .password(portalConfigurationDTO.getPassword())
                .businessId(portalConfigurationDTO.getBusinessId())
                .build();
        Intent rootIntent = Intent.builder()
                .intentName("ROOT")
                .description(portalConfigurationDTO.getIntent())
                .sequenceNumber(1)
                .build();
        List<String> steps = List.of(portalConfigurationDTO.getSteps().split(","));
        int sequenceNumber = rootIntent.getSequenceNumber();
        for (String step: steps) {
            sequenceNumber = sequenceNumber+1;
            Intent childIntent = Intent.builder()
                    .intentName("STEP_"+sequenceNumber)
                    .description(step)
                    .sequenceNumber(sequenceNumber)
                    .build();
            rootIntent.addChildIntent(childIntent);
        }
        portalConfiguration.addIntent(rootIntent);

        return portalConfiguration;
    }

    private PortalConfigurationResponse createPortalConfigurationResponse(PortalConfiguration portalConfiguration){
        List<Intent> intentList = portalConfiguration.getIntents();
        List<IntentDTO> intentDtoList = new ArrayList<>();
        Intent rootIntent = intentList.get(0);
        IntentDTO intentDTO = new IntentDTO(rootIntent.getIntentId(),rootIntent.getIntentName(),
                rootIntent.getSequenceNumber(),rootIntent.getDescription());
        List<Intent> childIntentList = rootIntent.getChildIntents();
        intentDtoList.add(intentDTO);
        for (Intent childIntent :childIntentList) {
            IntentDTO childIntentDTO = new IntentDTO(childIntent.getIntentId(),childIntent.getIntentName(),
                    childIntent.getSequenceNumber(),childIntent.getDescription());
            intentDtoList.add(childIntentDTO);
        }
        return new PortalConfigurationResponse(
                portalConfiguration.getPortalId(),
                portalConfiguration.getPortalName(),
                portalConfiguration.getPortalDescription(),
                portalConfiguration.getBaseUrl(),
                portalConfiguration.getBusinessId(),
                intentDtoList

        );
    }

    @Transactional
    public Intent createIntent(Intent intent, Integer portalId) {
        // Find the portal configuration
        PortalConfiguration portalConfiguration = getPortalConfigurationById(portalId);

        // Set portal configuration and validate
        intent.setPortalConfiguration(portalConfiguration);
        validateIntent(intent, portalConfiguration);

        return intentRepository.save(intent);
    }

    @Transactional
    public void addIntentToPortalConfiguration(Integer portalId, Intent intent) {
        PortalConfiguration portalConfiguration = getPortalConfigurationById(portalId);

        // Set portal configuration and validate
        intent.setPortalConfiguration(portalConfiguration);
        validateIntent(intent, portalConfiguration);

        portalConfiguration.addIntent(intent);
        portalConfigurationRepository.save(portalConfiguration);
    }

    @Transactional
    public void addChildIntent(Integer parentIntentId, Intent childIntent) {
        Intent parentIntent = intentRepository.findByIdWithPortalAndChildIntents(parentIntentId)
                .orElseThrow(() -> new EntityNotFoundException("Parent intent not found with ID: " + parentIntentId));

        // Set relationships and validate
        childIntent.setRootIntent(parentIntent);
        childIntent.setPortalConfiguration(parentIntent.getPortalConfiguration());
        validateIntent(childIntent, parentIntent.getPortalConfiguration());

        parentIntent.addChildIntent(childIntent);
        intentRepository.save(parentIntent);
    }

    @Transactional
    public List<Intent> getIntentsByPortalConfiguration(Integer portalId) {
        PortalConfiguration portalConfiguration = getPortalConfigurationById(portalId);
        return intentRepository.findByPortalConfiguration(portalConfiguration);
    }
}

