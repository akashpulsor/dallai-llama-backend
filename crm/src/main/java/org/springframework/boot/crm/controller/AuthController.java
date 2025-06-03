package org.springframework.boot.crm.controller;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.BusinessSizeMasterData;
import org.springframework.boot.crm.entity.DalaiLlamaLeads;
// Import the new exception
import org.springframework.boot.crm.exceptions.ResourceNotFoundException;
import org.springframework.boot.crm.service.BusinessManager;
import org.springframework.boot.crm.service.UserManager;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@CrossOrigin(origins = "*", maxAge = 3600)
@RestController
@Slf4j
@RequestMapping("/api/auth")
public class AuthController {

    private final UserManager userManager;

    private final BusinessManager businessManager;

    private final RestTemplate restTemplate;

    @Value("${google.analytics.ga4.measurementId}")
    private String ga4MeasurementId;

    @Value("${google.analytics.ga4.apiSecret}")
    private String ga4ApiSecret;

    public AuthController(UserManager userManager, BusinessManager businessManager,
                          RestTemplate restTemplate) {
        this.userManager = userManager;
        this.businessManager = businessManager;
        this.restTemplate = restTemplate;
    }

    @PostMapping("/interest")
    public DalaiLlamaLeads interest(@Valid @RequestBody DalaiLlamaLeadsDto dalaiLlamaLeadsDto){
        return this.businessManager.addDalaiLLamaLeads(dalaiLlamaLeadsDto);
    }

    //create controller to get interest in paginated way

    @PostMapping("/login")
    public LoginResponseDto authenticateUser(@Valid @RequestBody LoginRequestDto loginRequestDto) {
        return this.userManager.login(loginRequestDto);
    }

    @PostMapping("/register")
    public RegisterResponseDto registerUser(@Valid @RequestBody RegisterRequestDto registerRequestDto) {
        return this.userManager.register(registerRequestDto);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logoutUser() {
        LoginResponseDto loginResponseDto = this.userManager.logout();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, loginResponseDto.getAccessToken())
                .header(HttpHeaders.SET_COOKIE, loginResponseDto.getRefreshToken())
                .body(new MessageResponse("You've been signed out!"));
    }

    @PostMapping("/refresh-token")
    public TokenRefreshResponse refreshToken(@Valid @RequestBody TokenRefreshRequest request) {
        return this.userManager.refreshToken(request);
    }


    @GetMapping("/company-size")
    public List<BusinessSizeMasterDataDto> getCompanySizeMasterData() {
        return this.businessManager.getAllBusinessSizeMasterData();
    }

    @PostMapping("/verification-code")
    public VerificationCodeResponseDto getVerificationCode(VerificationCodeRequestDto verificationCodeRequestDto) {
        return this.userManager.sendVerificationCode(verificationCodeRequestDto);
    }


    @PostMapping("/verify-code")
    public String getVerificationCode(ValidateVerificationCodeRequestDto validateVerificationCodeRequestDto) {
        return this.userManager.verifyCode(validateVerificationCodeRequestDto);
    }

    @PostMapping("/update-password")
    public UserDto updatePassword(UpdatePasswordRequestDto updatePasswordRequestDto) {
        return this.userManager.updatePassword(updatePasswordRequestDto);
    }

    @GetMapping("/test")
    public String test(){
        return "app running";
    }

    // --- New Audio Controller ---
    @GetMapping("/play-audio")
    public ResponseEntity<byte[]> playAudio(@RequestParam("id") Integer audioId) {

            // Validate the audioId
            int callId=0;
            if(audioId==1) callId=27;
            if(audioId==2) callId=32;
            if(audioId==0) throw  new IllegalArgumentException("Invalid audioId provided. Please provide a valid audioId.");
            byte[] audioBytes = this.businessManager.downloadCallRecording(callId);;
            HttpHeaders headers = new HttpHeaders();
            // Assuming MP3 format; adjust if your audio files are different (e.g., audio/wav)
            headers.setContentType(MediaType.valueOf("audio/mpeg"));
            headers.setContentLength(audioBytes.length);
            return new ResponseEntity<>(audioBytes, headers, org.springframework.http.HttpStatus.OK);

    }
    // --- End New Audio Controller ---


    /**
     * Endpoint to track email opens using a 1x1 pixel.
     * This method logs the parameters, sends an event to GA4, and returns a transparent GIF.
     *
     * @param campaign The campaign identifier (e.g., "ecommerce-returns").
     * @param uniqueId A unique identifier for the email/recipient. This can serve as client_id in GA4.
     * @param source The source of the email (e.g., "marketing-automation", "crm").
     * @return A ResponseEntity containing a transparent 1x1 GIF.
     */
    @GetMapping("/email/open-pixel")
    public ResponseEntity<byte[]> trackEmailOpen(
            @RequestParam(name = "campaign", required = false) String campaign,
            @RequestParam(name = "uniqueId", required = false) String uniqueId,
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "event", required = false) String event) {

        // 1. Log the email open event for your internal tracking
        log.info("Email Open Tracked (Internal): Campaign='{}', UniqueId='{}', Source='{}'",
                campaign, uniqueId, source);

        // --- Data Persistence (Example - Choose your method) ---
        // emailOpenService.recordOpen(campaign, uniqueId, source);
        // ... (Your existing persistence logic) ...

        // 2. Send email open event to Google Analytics 4 (GA4)
        sendGa4Event(event, campaign, uniqueId, source);

        // 3. Return a transparent 1x1 GIF.
        byte[] transparentGif = new byte[]{
                (byte) 0x47, (byte) 0x49, (byte) 0x46, (byte) 0x38, (byte) 0x39, (byte) 0x61,
                (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x80, (byte) 0x00,
                (byte) 0x00, (byte) 0xff, (byte) 0xff, (byte) 0xff, (byte) 0x00, (byte) 0x00,
                (byte) 0x00, (byte) 0x2c, (byte) 0x00, (byte) 0x00, (byte) 0x00, (byte) 0x00,
                (byte) 0x01, (byte) 0x00, (byte) 0x01, (byte) 0x00, (byte) 0x00, (byte) 0x02,
                (byte) 0x02, (byte) 0x4c, (byte) 0x01, (byte) 0x00, (byte) 0x3b
        };

        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_GIF)
                .body(transparentGif);
    }

    /**
     * Sends an event to Google Analytics 4 using the Measurement Protocol asynchronously.
     * This method will be executed in a separate thread.
     *
     * @param eventName The name of the event (e.g., "email_open").
     * @param campaign The campaign parameter.
     * @param uniqueId The unique ID, used as client_id for GA4.
     * @param source The source parameter.
     */
    @Async // This annotation makes the method run in a separate thread
    public void sendGa4Event(String eventName, String campaign, String uniqueId, String source) {
        // The client_id is a unique identifier for a user or device.
        // For email opens, `uniqueId` from your email can serve this purpose
        // to tie the open to a specific recipient.
        String clientId = (uniqueId != null && !uniqueId.isEmpty()) ? uniqueId : "anonymous_email_user";

        // Event parameters
        Map<String, Object> eventParams = new HashMap<>();
        eventParams.put("campaign", campaign);
        eventParams.put("source", source);
        eventParams.put("medium", "email"); // Common medium for email opens
        eventParams.put("engagement_time_msec", "1"); // For instant events
        eventParams.put("session_id", uniqueId + "_" + System.currentTimeMillis()); // A unique session for each open

        // Construct the GA4 event payload
        Map<String, Object> event = new HashMap<>();
        event.put("name", eventName);
        event.put("params", eventParams);

        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("client_id", clientId);
        requestBody.put("events", Collections.singletonList(event));

        // Build the URL for the GA4 Measurement Protocol
        String ga4Url = String.format("https://www.google-analytics.com/mp/collect?measurement_id=%s&api_secret=%s",
                ga4MeasurementId, ga4ApiSecret);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<Map<String, Object>> requestEntity = new HttpEntity<>(requestBody, headers);

        try {
            restTemplate.postForEntity(ga4Url, requestEntity, String.class);
            log.info("GA4 event '{}' sent successfully for uniqueId: {}", eventName, uniqueId);
            DalaiLlamaLeadsDto dalaiLlamaLeadsDto = new DalaiLlamaLeadsDto();
            dalaiLlamaLeadsDto.setCampaign(campaign);
            dalaiLlamaLeadsDto.setSource(source);
            dalaiLlamaLeadsDto.setUniqueId(uniqueId);
            this.businessManager.addDalaiLLamaLeads(dalaiLlamaLeadsDto);
            log.info("Pixel Entry added in database '{}' sent successfully for uniqueId: {}", eventName, uniqueId);
        } catch (Exception e) {
            log.error("Failed to send GA4 event '{}' for uniqueId: {}. Error: {}", eventName, uniqueId, e.getMessage(), e);
        }
    }
}