package org.springframework.boot.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import jakarta.validation.ValidationException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.*;
import org.springframework.boot.crm.repository.*;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.MalformedURLException;
import java.net.URI;
import java.net.URL;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrowserAgentService {
    private final BrowserSessionRepository browserSessionRepository;
    private final BrowserActionRepository browserActionRepository;
    private final SessionRepository sessionRepository;
    private final PortalService portalService;
    private final LLMIntegrationService llmIntegrationService;
    private final ObjectMapper objectMapper;
    private final LlmService llmService;
    private final ApiCallerService apiCallerService;
    private final RestTemplate restTemplate;
    /**
     * Creates a new browser session
     */
    @Transactional
    public BrowserSession createBrowserSession(BrowserSessionCreateRequest browserSessionCreateRequest) throws JsonProcessingException {
        String url = "http://localhost:3000/browser/launch";
        Session session = createSession(browserSessionCreateRequest);
        sessionRepository.save(session);
        LlmData llmData = llmService.getLlmData(browserSessionCreateRequest.getBusinessId(), browserSessionCreateRequest.getLlmId());
        PortalConfiguration portal = this.portalService.getPortalConfigurationById(browserSessionCreateRequest.getPortalId());
        BrowserSession browserSession = createBrowserSession(browserSessionCreateRequest,session);

        browserSession =  browserSessionRepository.save(browserSession);
        LaunchBrowserDto launchBrowserDto = new LaunchBrowserDto(portal.getPortalId(), llmData.getLlmId(), browserSession.getSessionId(), session.getId(),portal.getBaseUrl());
        ResponseEntity<String> responseEntity =  apiCallerService.callPostApiWithObject(url,launchBrowserDto);
        if (!responseEntity.getStatusCode().is2xxSuccessful()) {}

        log.info("POST request with object successful!");

        LaunchBrowserResponseDto launchBrowserResponseDto = objectMapper.readValue(responseEntity.getBody(), LaunchBrowserResponseDto.class);
        browserSession.setBrowserRunId(launchBrowserResponseDto.getSessionId());

        return browserSessionRepository.save(browserSession);
    }

    public DomContextResponse getDomContext(DomContextRequest domContextRequest) {
        //Intent intent = intentRepository.findById(domContextRequest.getIntentId())
        //        .orElseThrow(() -> new EntityNotFoundException("Intent not found with ID: " + domContextRequest.getIntentId()));
        //Portal portal = portalService.getPortalData(domContextRequest.getIntentId());
        LlmData llmData = llmService.getLlmData(domContextRequest.getBusinessId(), domContextRequest.getLlmId());
        //String llmResponse =this.llmIntegrationService.generateDomDescription(llmData,domContextRequest.getDomData(),
        //        intent.getDescription(),portal.getPortalDescription());
        //return objectMapper.convertValue(llmResponse, DomContextResponse.class);
        return null;
    }

    public GenerateDescriptionResponseDto generateContext(GenerateDescriptionRequestDto generateDescriptionRequestDto) {

        LlmData llmData = llmService.getLlmData(generateDescriptionRequestDto.getBusinessId(), generateDescriptionRequestDto.getLlmId());
        String llmResponse =this.llmIntegrationService.generatePortalDescription(llmData, generateDescriptionRequestDto.getPortalUrl());
        return objectMapper.convertValue(llmResponse, GenerateDescriptionResponseDto.class);
    }

    public ValidateUrlResponseDto validateUrl(int businessId, String urlToValidate) {
        if (urlToValidate == null || urlToValidate.trim().isEmpty()) {
            throw new ValidationException("URL parameter cannot be empty.");
        }
        if (!isValidURL(urlToValidate)) {
            throw new ValidationException("Invalid URL format.");
        }
        if (!isServerReachable(urlToValidate)) {
            throw new ValidationException("Server at the given URL is not reachable or did not respond within the timeout.");
        }
        log.info("URL is valid and server is reachable: {}", urlToValidate);
        ValidateUrlResponseDto validateUrlResponseDto = new ValidateUrlResponseDto();
        validateUrlResponseDto.setMessage("URL is valid and server is reachable.");
        return validateUrlResponseDto;
    }



    private boolean isValidURL(String url) {
        try {
            new URL(url);
            return true;
        } catch (MalformedURLException e) {
            return false;
        }
    }

    private boolean isServerReachable(String url) {
        try {
            URI uri = UriComponentsBuilder.fromUriString(url).build().toUri();
            Instant start = Instant.now();
            ResponseEntity<String> response = restTemplate.getForEntity(uri, String.class);
            Instant end = Instant.now();
            long responseTime = Duration.between(start, end).toMillis();
            log.info("Server at {} responded with status {} in {} ms.", url, response.getStatusCode(), responseTime);
            return response.getStatusCode().is2xxSuccessful();
        } catch (Exception e) {
            log.warn("Error while trying to reach {}: {}", url, e.getMessage());
            return false;
        }
    }

    private Session createSession(BrowserSessionCreateRequest browserSessionCreateRequest){
        Session session = new Session();
        session.setUserId(browserSessionCreateRequest.getBusinessId());
        session.setStatus(Session.SessionStatus.ACTIVE);
        session.setStartTime(LocalDateTime.now());
        session.setCreatedAt(LocalDateTime.now());
        session.setSessionType(Session.SessionType.BROWSER_AGENT);
        return session;
    }

    private BrowserSession createBrowserSession(BrowserSessionCreateRequest browserSessionCreateRequest,
                                                Session session){
        BrowserSession browserSession = new BrowserSession();
        browserSession.setPortalId(browserSessionCreateRequest.getPortalId());
        browserSession.setCampaignId(browserSessionCreateRequest.getCampaignId());
        browserSession.setStatus(Session.SessionStatus.ACTIVE);
        browserSession.setSession(session);
        return browserSession;
    }




}
