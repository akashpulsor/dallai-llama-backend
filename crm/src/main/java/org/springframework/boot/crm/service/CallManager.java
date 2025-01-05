package org.springframework.boot.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twilio.Twilio;
import com.twilio.http.HttpMethod;
import com.twilio.type.PhoneNumber;
import com.twilio.rest.api.v2010.account.Call;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
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

    private final CampaignRunService campaignRunService;




    @Value("${app.hostname}")
    private String hostname;

    public CallManager(ApplicationEventPublisher applicationEventPublisher, ToolsService toolsService,
                        CallLogService callLogService,   CampaignRunService campaignRunService) {
        this.applicationEventPublisher = applicationEventPublisher;
        twilioOpenAiMap = new ConcurrentHashMap<>();
        this.toolsService = toolsService;
        this.callLogService = callLogService;
        this.campaignRunService = campaignRunService;
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
        String webhookUrl = hostname + "/api/call/incoming?authToken=" + authToken+ "&campaignRunId="+campaignRunData.getCampaignRunId()+ "&businessId="+campaignRunData.getBusinessId()+ "&leadId="+leadData.getLeadId();

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
                               String authToken,int businessId, int leadId )  {
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
                + "<Parameter name=\"businessId\" value=\"" + businessId + "\" />"
                + "<Parameter name=\"leadId\" value=\"" + leadId + "\" />"
                +"</Stream>"
                + "</Connect>"
                + "</Response>";
    }

    public String createAuthToken(int leadId, int businessId, int campaignId,
                                  TwilioData twilioData) {
        //TODO create jwt token

        return "jwtToken";
    }

    public  void handleTwilioEvent(TwilioStartEventDto twilioStartEventDto,String systemMessage, LlmData llmData, LeadData leadData, CampaignData campaignData) throws IOException, InterruptedException {
        log.info("twilio start event Received - {}", twilioStartEventDto);
        OpenAiRequestDto openAiRequestDto = createOpenAiInit(twilioStartEventDto.getTwilioStartMediaMessage(), systemMessage);
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(openAiRequestDto);
        log.info("open ai start event Received - {}", openAiRequestDto);
        RealTimeSession realTimeSession = new RealTimeSession(json, twilioStartEventDto, this.applicationEventPublisher,llmData);
        this.twilioOpenAiMap.put(twilioStartEventDto.getTwilioStartMediaMessage().getStreamSid(), realTimeSession);
        sendUserInformation(realTimeSession,twilioStartEventDto.getTwilioStartMediaMessage(),leadData, campaignData);
    }

    public void sendOpenAiRealtimeSession(TwilioMediaEventDto twilioMediaEventDto, String json) {
        RealTimeSession openAiRealTimeSession = twilioOpenAiMap.get(twilioMediaEventDto.getMediaEventDto().getStreamSid());
        openAiRealTimeSession.getWebSocket().sendText(json, true);
    }

    public void sendTwilioRealtimeSession(OpenAiAudioEvent openAiAudioEvent,Map<String,Object> audioDelta,Map<String, String> audioData) throws IOException {
        if(twilioOpenAiMap.containsKey(openAiAudioEvent.getTwilioStartEventDto().getTwilioStartMediaMessage().getStreamSid())){
            RealTimeSession openAiRealTimeSession = twilioOpenAiMap.get(openAiAudioEvent.getTwilioStartEventDto().getTwilioStartMediaMessage().getStreamSid());
            WebSocketSession twilioSession = openAiRealTimeSession.getTwilioStartEventDto().getSession();
            if(twilioSession != null && twilioSession.isOpen()) {
                ObjectMapper objectMapper = new ObjectMapper();
                audioDelta.put("media", audioData);
                String json = objectMapper.writeValueAsString(audioDelta);
                log.info("Sending data to twilio - {}", json);
                twilioSession.sendMessage(new TextMessage(json));
                return;
            }
        }
        else {
            log.info("Session Seems unavailable");
        }

    }


    private void sendUserInformation(RealTimeSession openAiRealTimeSession,TwilioStartMessageDto twilioStartMessageDto,LeadData leadData, CampaignData campaignData) throws JsonProcessingException {
        OpenAiCreateConversationDto openAiCreateConversationDto = new OpenAiCreateConversationDto();
        openAiCreateConversationDto.setEvent_id(twilioStartMessageDto.getStreamSid());
        openAiCreateConversationDto.setType("conversation.item.create");
        OpenAiCreateConversationDto.Item item = new OpenAiCreateConversationDto.Item();
        item.setId("msg_001");
        item.setType("message");
        item.setStatus("completed");
        item.setRole("user");
        openAiCreateConversationDto.setItem(item);
        OpenAiCreateConversationDto.Item.Content content = new OpenAiCreateConversationDto.Item.Content();
        content.setText("Hello "+ leadData.getLeadName() + " " + campaignData.getFirstMessage() + "current lead Id is " + leadData.getLeadId());
        content.setType("input_text");
        item.getContent().add(content);
        ObjectMapper objectMapper = new ObjectMapper();
        String json =objectMapper.writeValueAsString(openAiCreateConversationDto);
        log.info("Open Ai Create conversation - {}", json);
        openAiRealTimeSession.getWebSocket().sendText(json, true);
    }

    private OpenAiRequestDto createOpenAiInit(TwilioStartMessageDto twilioStartMessageDto, String systemMessage) {
        OpenAiRequestDto openAiRequestDto = new OpenAiRequestDto();
        openAiRequestDto.setEvent_id(twilioStartMessageDto.getStreamSid());
        openAiRequestDto.setType("session.update");
        OpenAiRequestDto.OpenAISession openAiSession = getOpenAISession(systemMessage);
        openAiRequestDto.setSession(openAiSession);
        log.info("Created initial open ai request - {}",openAiRequestDto);
        return openAiRequestDto;
    }

    private OpenAiRequestDto.OpenAISession getOpenAISession(String systemMessage) {
        OpenAiRequestDto.OpenAISession openAiSession = new OpenAiRequestDto.OpenAISession();
        openAiSession.setInput_audio_format("g711_ulaw");
        openAiSession.setOutput_audio_format("g711_ulaw");
        openAiSession.setVoice("alloy");
        openAiSession.setInstructions(systemMessage);
        openAiSession.setTool_choice("auto");
        openAiSession.setTemperature(0.8);
        List<String> modals = new ArrayList<>();
        modals.add("text");
        modals.add("audio");
        openAiSession.setModalities(modals);
        Map<String, String> map = new HashMap<>();
        openAiSession.setTools(this.getToolsService().getTools());
        map.put("type", "server_vad");
        openAiSession.setTurn_detection(map);
        return openAiSession;
    }
    public String createSystemMessage(AgentData agentData, CampaignData campaignData, BusinessData businessData) {
        return "### Agent Id\n" +
                agentData.getAgentId() +
                "### Role\n" +
                agentData.getRole() +
                "### Persona\n" +
                agentData.getPersona() +
                "### Conversation Guidelines\n" +
                campaignData.getConversationGuideLines() +
                "### Business Id\n" +
                businessData.getBusinessId() +
                "### Company Details\n" +
                businessData.getBusinessName() +
                "### Business Details\n" +
                businessData.getBusinessName()+
                "### Business Activity Description\n" +
                businessData.getBasicActivityDescription()+
                "### You are working on campaign\n" +
                "### Campaign Id\n" +
                campaignData.getCampaignId()+
                "### Campaign Name\n" +
                campaignData.getCampaignName() +
                "### Campaign Desc\n" +
                campaignData.getCampaignDesc() +
                "### Campaign Prompt\n" +
                campaignData.getCampaignPrompt() +
                "### Campaign Aim\n" +
                campaignData.getCampaignAim() +
                "### First Message\n" +
                campaignData.getFirstMessage() +
                "### Handling FAQs\n" +
                "Use the function \\`updateWhatsApp\\` to respond to update whats app number." +
                "Use the function \\`queries\\` to respond to common customer queries." +
                "### Send product list \n" +
                "if not asked,  Before getting product list ask for whats app number then Use the function \\`getProductList\\` to respond to common customer queries."+
                "### Place orders \n" +
                "if not asked,  Before getting product list ask for whats app number then Use the function \\`placeOrders\\` to respond to common customer queries."+
                "### Send Invoice and Bill r\n" +
                "if not asked,getting product list ask for whats app number then Use the function \\`sendInvoice\\` to respond to common customer queries."
                ;
    }





}
