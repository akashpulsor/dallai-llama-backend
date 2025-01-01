package org.springframework.boot.crm.service;

import com.twilio.Twilio;
import com.twilio.http.HttpMethod;
import com.twilio.type.PhoneNumber;
import com.twilio.rest.api.v2010.account.Call;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.crm.entity.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Data
@Slf4j
@Component
public class CallManager {

    private ConcurrentHashMap<String,RealTimeSession> twilioOpenAiMap;

    private final ApplicationEventPublisher applicationEventPublisher;

    private final ToolsService toolsService;

    private final CallLogService callLogService;


    private final AgentManager agentManager;

    private final LeadManager leadManager;

    @Value("${app.hostname}")
    private String hostname;
    private final BusinessManager businessManager;

    public CallManager(ApplicationEventPublisher applicationEventPublisher, ToolsService toolsService,
                        CallLogService callLogService,  AgentManager agentManager,
                        LeadManager leadManager,  BusinessManager businessManager) {
        this.applicationEventPublisher = applicationEventPublisher;
        twilioOpenAiMap = new ConcurrentHashMap<>();
        this.toolsService = toolsService;
        this.callLogService = callLogService;
        this.agentManager = agentManager;
        this.leadManager = leadManager;
        this.businessManager = businessManager;
    }




    public CallLog makeCall(TwilioData twilioData, LeadData leadData, CampaignData campaignData, CampaignRunData campaignRunData) throws URISyntaxException {
        Twilio.init(twilioData.getAccountSid(), twilioData.getAccountAuthToken());

        // Create auth token for webhook
        String authToken = createAuthToken(
                leadData.getLeadId(),
                campaignData.getBusinessId(),
                campaignData.getCampaignId(),
                twilioData
        );

        // Build webhook URL with auth token
        String webhookUrl = hostname + "/api/call/incoming?authToken=" + authToken+ "&campaignRunId="+campaignRunData.getCampaignRunId();

        // Create call with Twilio
        Call call = Call.creator(
                        new PhoneNumber(leadData.getPhoneCountryCode()+leadData.getLeadPhone()),      // To number
                        new PhoneNumber(twilioData.getBusinessNumber()), // From number
                        new URI(webhookUrl)                             // Webhook URL
                )
                .setStatusCallback(new URI(hostname + "/api/call/status"))
                .setStatusCallbackEvent(List.of("initiated", "ringing", "answered", "completed"))
                .setStatusCallbackMethod(HttpMethod.POST)
                .create();


        campaignRunData.setCallSId(call.getSid());
        return this.callLogService.createCallLog("OUT_BOUND",
                campaignRunData.getCampaignRunId(),
                leadData.getLeadId(),  call.getSid(),
                twilioData.getBusinessNumber(), leadData.getLeadPhone() );

    }

    public String incomingCall(String host,int campaignRunId,
                               String authToken ) {
        //TODO decrypt the auth token
        String url = "wss://"+host+"/media-stream?"+ "authToken="+authToken+"&campaignRunId="+campaignRunId;
        url = "wss://"+host+"/api/call/media-stream";
        return  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Response>"
                + "<Say>Please wait while we connect your call to the A. I. voice assistant, powered by Twilio and the Open-A.I. Realtime API</Say>"
                + "<Pause length=\"1\"/>"
                + "<Say>O.K. you can start talking!</Say>"
                + "<Connect>"
                + "<Stream url=\""+url+"\" >"
                + "<Parameter name=\"authToken\" value=\"" + authToken + "\" />"
                + "<Parameter name=\"campaignRunId\" value=\"" + campaignRunId + "\" />"
                +"</Stream>"
                + "</Connect>"
                + "</Response>";
    }

    public String createAuthToken(int leadId, int businessId, int campaignId,
                                  TwilioData twilioData) {
        //TODO create jwt token

        return "jwtToken";
    }



}
