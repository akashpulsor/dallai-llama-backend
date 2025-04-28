package org.springframework.boot.crm.service;

import ch.qos.logback.classic.spi.CallerData;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.twilio.Twilio;
import com.twilio.http.HttpMethod;
import com.twilio.rest.api.v2010.account.Recording;
import com.twilio.rest.api.v2010.account.Transcription;
import com.twilio.type.PhoneNumber;
import com.twilio.rest.api.v2010.account.Call;
import jakarta.transaction.Transactional;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.*;
import org.springframework.boot.crm.exceptions.ToolExecutionException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.LocalDate;
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

    private final TranscriptionService transcriptionService;

    @Value("${app.hostname}")
    private String hostname;

    public CallManager(ApplicationEventPublisher applicationEventPublisher, ToolsService toolsService,
                        CallLogService callLogService, TranscriptionService transcriptionService) {
        this.applicationEventPublisher = applicationEventPublisher;
        twilioOpenAiMap = new ConcurrentHashMap<>();
        this.toolsService = toolsService;
        this.callLogService = callLogService;
        this.transcriptionService=transcriptionService;
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
                .setRecordingChannels("dual")
                .setRecord(true)
                .setRecordingStatusCallback(hostname + "/api/call/recording-status")
                .setRecordingStatusCallbackMethod(HttpMethod.POST)
                .setRecordingStatusCallbackEvent(List.of("completed"))
                .create();


        campaignRunData.setCallSId(call.getSid());
        return this.callLogService.createCallLog(callType,
                campaignRunData.getCampaignRunId(),
                leadData.getLeadId(),  call.getSid(),
                twilioData.getBusinessNumber(), leadData.getLeadPhone() );

    }

    public String callTool(FunctionCallDto functionCallDto, TwilioStartEventDto twilioStartEventDto) {
        return this.toolsService.executeTool(functionCallDto.getName(),functionCallDto,twilioStartEventDto);
    }
    public String incomingCall(String host,int campaignRunId,
                               String authToken,int businessId, int leadId, String callType )  {
        //TODO decrypt the auth token
        String url = "wss://"+host+"/api/call/media-stream";
        String transcriptionCallback = host + "/api/call/transcription-callback";
        return  "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<Response>"
                + "<Say>Please wait while we connect your call to the A. I. voice assistant, powered by Twilio and the Open-A.I. Realtime API</Say>"
                + "<Pause length=\"1\"/>"
                + "<Say>O.K. you can start talking!</Say>"

                + "<Connect>"
                + "<Stream url=\""+url+"\"  >"
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

    @Transactional
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
        CallStatus callStatus = new CallStatus();
        callStatus.setStatus("ENDED");
        callStatus.setCallLog(callLog);
        callStatus.setTimestamp(LocalDateTime.now());
        callLog.getStatusHistory().add(callStatus);
        this.callLogService.saveCallLog(callLog);
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


    public CallLog getCallLog(String callType, int campaignRunId, int leadId){
        return this.callLogService.getCallLog(callType,
                campaignRunId,
                leadId
        );
    }

    public void sendToolResponse(FunctionCallDto functionCallDto, String functionResponse) throws IOException {
        Map<String, Object> toolCall = toolsService.createToolResponse(functionCallDto.getCallId(), functionResponse);
        Map<String, String> argumentMap = functionCallDto.getParsedArguments();
        if( argumentMap!=null && !argumentMap.containsKey("streamId") ) {
            throw new ToolExecutionException("Stream id Not found");
        }
        assert argumentMap != null;
        String streamId = argumentMap.get("streamId");
        ObjectMapper objectMapper = new ObjectMapper();
        String json = objectMapper.writeValueAsString(toolCall);
        log.info("Response to tool call for function name -{}- {}",functionCallDto ,json);
        RealTimeSession realTimeSession = this.twilioOpenAiMap.get(streamId);
        realTimeSession.getWebSocket().sendText(json, true);
        if(functionCallDto.getName().equals("disconnect_call")) {
            TwilioCloseEvent twilioCloseEvent = new TwilioCloseEvent(this, realTimeSession.getTwilioStartEventDto().getSession());
            this.applicationEventPublisher.publishEvent(twilioCloseEvent);
        }

    }
    public CallLog getCallLog(int callId){
        return this.callLogService.getCallLog(callId);
    }

    public CallLog getCallLog(String callSid){
        return this.callLogService.getCallLogByCallSid(callSid);
    }

    public Page<CallLog> getPaginatedCallLogs(int campaignRunId, int page, int size) {
        return callLogService.getPaginatedCallLogs(campaignRunId, page, size);
    }

    public List<CallLog> getPaginatedCallLogsList(int campaignRunId) {
        return callLogService.getPaginatedCallLogsList(campaignRunId);
    }

    public long totalCalls(int businessId, LocalDate startDate, LocalDate endDate) {
        return this.callLogService.callLogCount(businessId, startDate, endDate);
    }

    public void sendOpenAiRealtimeSession(TwilioMediaEventDto twilioMediaEventDto, String json) {
        RealTimeSession openAiRealTimeSession = twilioOpenAiMap.get(twilioMediaEventDto.getMediaEventDto().getStreamSid());
        if(openAiRealTimeSession!=null) {
            openAiRealTimeSession.getWebSocket().sendText(json, true);
        }

    }

    public TranscriptionDto getTranscriptionData(TwilioData twilioData, LlmData llmData,CallLog callLog,String callSid,  String recordingSid,
                                     String recordingStatus,
                                     String recordingUrl)  {
        log.info("Recording status update for call {}: {} - Recording SID: {}",
                callSid, recordingStatus, recordingSid);
        Twilio.init(twilioData.getAccountSid(), twilioData.getAccountAuthToken());
        // Fetch the recording metadata
        Recording recording = Recording.fetcher(recordingSid).fetch();
        log.info("Recording metadata: {}", recording);

        String inboundUrl = recordingUrl + ".wav?Download=true&Channels=mono&Channel=0";
        byte[] inboundAudioData = TranscriptionUtils.downloadAudio(inboundUrl, twilioData);
        String inboundTranscription =TranscriptionUtils.transcribeAudio(inboundAudioData, llmData);
        String outboundUrl = recordingUrl + ".wav?Download=true&Channels=mono&Channel=1";
        byte[] outboundAudioData = TranscriptionUtils.downloadAudio(outboundUrl, twilioData);
        String outboundTranscription =TranscriptionUtils.transcribeAudio(outboundAudioData, llmData);
        return new TranscriptionDto(
                recording,
                callLog,
                inboundTranscription,
                outboundTranscription
        );
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
            }
            else {
                log.info("Session seems closed");
            }
        }
        else {
            log.info("Session Seems unavailable");
        }

    }


    public CallLog updateCallStatus(CallStatusDto callStatusDto) {
        CallLog callLog=this.callLogService.getCallLogByCallSid(callStatusDto.getCallSid());
        CallStatus callStatus = new CallStatus();
        callStatus.setStatus(callStatusDto.getCallStatus());
        callStatus.setTimestamp(LocalDateTime.now());
        callLog.getStatusHistory().add(callStatus);
        callLog.setEndTime(LocalDateTime.now());
        callStatus.setCallLog(callLog);
        this.callLogService.saveCallLog(callLog);
        log.info("Call log updated - {}", callLog);
        this.applicationEventPublisher.publishEvent(new CallLogEvent(this, callLog));
        return callLog;
    }

    public void addBillingInformation(OpenAiResponseDoneDto openAiResponseDoneDto, TwilioStartEventDto twilioStartEventDto) {
        CallLog callLog = getCallLog(twilioStartEventDto.
                        getTwilioStartMediaMessage().getStart().getCustomParameters().getCallType(),
                twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getCampaignRunId(),
                twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getLeadId()
        );
        BillingDataEvent billingDataEvent = new BillingDataEvent(this,openAiResponseDoneDto.getResponse().getUsage(), callLog.getCallLogId());
        this.applicationEventPublisher.publishEvent(billingDataEvent);
    }

    public byte[] downloadRecording( int businessId,
                                     int campaignRunId,
                                     int callId, TwilioData twilioData) {
        TranscriptionData transcriptionData = this.transcriptionService.getTranscriptionData(businessId, campaignRunId, callId);
        String inboundUrl = transcriptionData.getMediaUrl() + ".wav";
        return TranscriptionUtils.downloadAudio(inboundUrl, twilioData);
    }

    private CallLog updateStreamId(TwilioStartEventDto twilioStartEventDto,CallLog callLog){
        callLog.setStreamId(twilioStartEventDto.getTwilioStartMediaMessage().getStreamSid());
        return this.callLogService.saveCallLog(callLog);
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
        // Get tools from ToolService and set them in the session
        List<Map<String, Object>> tools = this.getToolsService().getTools();

        openAiSession.setTools(tools);
        map.put("type", "server_vad");
        openAiSession.setTurn_detection(map);
        return openAiSession;
    }
    public String createSystemMessage(AgentData agentData, CampaignData campaignData, BusinessData businessData, CampaignRunData campaignRunData
                                      ) {
        return "### Agent Id\n" +
                agentData.getAgentId() +
                "### Agent Name\n" +
                agentData.getAgentName() +
                "### Role\n" +
                agentData.getRole() +
                "### Persona\n" +
                agentData.getPersona() +
                "### Conversation Guidelines\n" +
                campaignData.getConversationGuideLines() +
                "Speak slow don't rush" +
                "When ever user speaks stop speaking and listen to customer then respond, you should sound human, not machine, do not repeat information again till the time customer ask, close the call, do not irritate customer \n" +
                "Please be judicious when you want to disconnect the call, sometimes lead is listening you and you end cutting the call assuming the connection is weak and their is no response, i would suggest figure out a way so that you know lead is listening to you and when lead is not listening and you need to cut the call  \n" +
                "If voice is feeble, gracefully cut the call \n" +
                "At no point of time user should feel you are machine, if he says something like thing tell joke about it and gracefully cut the call \n" +
                "After you speak please wait for some time,  and also please add back fillers like huh, aah, to feel more human, rather than assuming things ask questions \n" +
                "If lead is speaking please add back filler such as ahh hmm, and once lead completes speaking speak after understanding what lead said" +
                "Use transcription of input audio to understand the  number that client is speaking"+
                "Use transcription of input audio to understand the  email that client is speaking, while when client is telling email at means at the rate @ "+
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
                "### Handling FAQs\n" +
                "Use the function \\`get_metadata\\` to get metadata, the required parameter of this function is natural language query which you can pass.\n"+
                "Use the function \\`disconnect_call\\` Call this function after the call of  function \\`get_metadata\\` you will get stream Id from it's response,pass stream id returned from \\`get_metadata\\`  to disconnect call\n"+
                "Use the function \\`update_lead_data\\` Call this function when ever you want to update email and whatsapp of lead, the argument for this is lead Id stream id, whatsapp number you asked from lead, ask complete whats app number with country code, then reiterate what you heard when lead says yes this number is right update call the function, same goes for email Id as well, there you won't need country code but re iterate complete email and update when client say yes, call this function after client gives data and then repeat what lead says, then ask if this is correct, if lead responds by affirmative action then only call this function\n";
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
