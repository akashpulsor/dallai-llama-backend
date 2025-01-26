package org.springframework.boot.crm.service;

import ch.qos.logback.classic.spi.CallerData;
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

    @Value("${app.hostname}")
    private String hostname;

    public CallManager(ApplicationEventPublisher applicationEventPublisher, ToolsService toolsService,
                        CallLogService callLogService) {
        this.applicationEventPublisher = applicationEventPublisher;
        twilioOpenAiMap = new ConcurrentHashMap<>();
        this.toolsService = toolsService;
        this.callLogService = callLogService;
    }




    public CallLog makeCall(TwilioData twilioData, LeadData leadData, CampaignData campaignData, CampaignRunData campaignRunData, String callType) throws URISyntaxException {
        Twilio.init(twilioData.getAccountSid(), twilioData.getAccountAuthToken());

        // Create auth token for webhook
        String authToken = createAuthToken(
                leadData.getLeadId(),
                campaignData.getBusinessId(),
                campaignData.getCampaignId(),
                twilioData
        );

        // Build webhook URL with auth token
        String webhookUrl = hostname + "/api/call/incoming?authToken=" + authToken+ "&campaignRunId="+campaignRunData.getCampaignRunId()+ "&businessId="+campaignRunData.getBusinessId()+ "&leadId="+leadData.getLeadId()+ "&callType="+callType;

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
        return this.callLogService.createCallLog(callType,
                campaignRunData.getCampaignRunId(),
                leadData.getLeadId(),  call.getSid(),
                twilioData.getBusinessNumber(), leadData.getLeadPhone() );

    }

    public String incomingCall(String host,int campaignRunId,
                               String authToken,int businessId, int leadId, String callType )  {
        //TODO decrypt the auth token
        String url = "wss://"+host+"/media-stream?"+ "authToken="+authToken+"&campaignRunId="+campaignRunId;
        url = "wss://"+host+"/api/call/media-stream?"+ "authToken="+authToken+"&campaignRunId="+campaignRunId+"&leadId="+leadId+"&callType="+callType;
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
                + "<Parameter name=\"callType\" value=\"" + callType + "\" />"
                +"</Stream>"
                + "</Connect>"
                + "</Response>";
    }

    public String createAuthToken(int leadId, int businessId, int campaignId,
                                  TwilioData twilioData) {
        //TODO create jwt token

        return "jwtToken";
    }

    public void removeSession(int leadId, int campaignRunId, String callType) {
        CallLog callLog = this.callLogService.getCallLog(callType,
                campaignRunId,
                leadId
        );
        log.info("Size before removing stream Id - {}", twilioOpenAiMap.size());
        twilioOpenAiMap.remove(callLog.getStreamId());
        log.info("Size After removing stream Id  - {}", twilioOpenAiMap.size());
        StopCallEvent stopCallEvent = new StopCallEvent(this, callLog.getCallLogId());
        this.applicationEventPublisher.publishEvent(stopCallEvent);
        //CallStatus callStatus = new CallStatus();
        //callStatus.setStatus("ENDED");
        //callStatus.setCallLog(callLog);
        //callLog.getStatusHistory().add(callStatus);
        //this.callLogService.saveCallLog(callLog);
    }

    public  void handleTwilioEvent(TwilioStartEventDto twilioStartEventDto,String systemMessage, LlmData llmData, LeadData leadData,
                                   CampaignData campaignData) throws IOException, InterruptedException {
        CallLog callLog = getCallLog(twilioStartEventDto.
                        getTwilioStartMediaMessage().getStart().getCustomParameters().getCallType(),
                twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getCampaignRunId(),
                twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getLeadId()
        );
        systemMessage +="### Call log id\n" +
                callLog.getCallLogId();
        log.info("twilio start event Received - {}", twilioStartEventDto);
        OpenAiRequestDto openAiRequestDto = createOpenAiInit(twilioStartEventDto.getTwilioStartMediaMessage(), systemMessage);
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(openAiRequestDto);
        log.info("open ai start event Received - {}", openAiRequestDto);
        RealTimeSession realTimeSession = new RealTimeSession(json, twilioStartEventDto, this.applicationEventPublisher,llmData);
        this.twilioOpenAiMap.put(twilioStartEventDto.getTwilioStartMediaMessage().getStreamSid(), realTimeSession);
        sendUserInformation(realTimeSession,twilioStartEventDto.getTwilioStartMediaMessage(),leadData, campaignData);
        callLog = updateStreamId(twilioStartEventDto,callLog);
        log.info("Call log is - {}", callLog);
    }

    private CallLog updateStreamId(TwilioStartEventDto twilioStartEventDto,CallLog callLog){
        callLog.setStreamId(twilioStartEventDto.getTwilioStartMediaMessage().getStreamSid());
        return this.callLogService.saveCallLog(callLog);
    }

    public CallLog getCallLog(String callType, int campaignRunId, int leadId){
        return this.callLogService.getCallLog(callType,
                campaignRunId,
                leadId
        );
    }

    public CallLog getCallLog(int callId){
        return this.callLogService.getCallLog(callId);
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

    public void addBillingInformation(OpenAiResponseDoneDto openAiResponseDoneDto, TwilioStartEventDto twilioStartEventDto) {
        CallLog callLog = getCallLog(twilioStartEventDto.
                        getTwilioStartMediaMessage().getStart().getCustomParameters().getCallType(),
                twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getCampaignRunId(),
                twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getLeadId()
        );
        BillingDataEvent billingDataEvent = new BillingDataEvent(this,openAiResponseDoneDto.getResponse().getUsage(),
                callLog.getCallLogId());
        this.applicationEventPublisher.publishEvent(billingDataEvent);
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
    public String createSystemMessage(AgentData agentData, CampaignData campaignData, BusinessData businessData, CampaignRunData campaignRunData
                                      ) {
        return "### Agent Id\n" +
                agentData.getAgentId() +
                "### Role\n" +
                agentData.getRole() +
                "### Persona\n" +
                agentData.getPersona() +
                "### Conversation Guidelines\n" +
                campaignData.getConversationGuideLines() +
                "When ever user speaks stop speaking and listen to customer then respond, you should sound human, not machine, do not repeat information again till the time customer ask, close the call, do not irritate customer \n" +
                "If voice is feeble, gracefully cut the call" +
                "At no point of time user should feel you are machine, if he says something like thing tell joke about it and gracefully cut the call" +
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
                "### Language of conversation\n" +
                campaignRunData.getLanguage()+
                "### First Message\n" +
                campaignData.getFirstMessage() +
                "### Campaign Run id\n" +
                campaignRunData.getCampaignRunId() +
                "### Handling FAQs\n";// +
                //"Use the function \\`updateWhatsApp\\` to respond to update whats app number." +
                //"Use the function \\`queries\\` to respond to common customer queries." +
                //"### Send product list \n" +
                //"if not asked,  Before getting product list ask for whats app number then Use the function \\`getProductList\\` to respond to common customer queries."+
                //"### Place orders \n" +
               // "if not asked,  Before getting product list ask for whats app number then Use the function \\`placeOrders\\` to respond to common customer queries."+
                //"### Send Invoice and Bill r\n" +
                //"if not asked,getting product list ask for whats app number then Use the function \\`sendInvoice\\` to respond to common customer queries."+
                //"### Send information about Input and output Token, and total charger\n" +
                //"give input token, output token and total token, total charges and call Id in response as part of meta data of all the responses\n";
    }





}
