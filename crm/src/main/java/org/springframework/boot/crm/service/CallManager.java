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

    // This map is already thread-safe and supports multiple simultaneous calls.
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




    public CallLog makeCall(TwilioData twilioData, LeadData leadData, CampaignData campaignData,
                            CampaignRunData campaignRunData, String callType, AgentData agentData, LlmData llmData) throws URISyntaxException, IOException {
        Twilio.init(twilioData.getAccountSid(), twilioData.getAccountAuthToken());

        // Create auth token for webhook
        String authToken = createAuthToken(
                leadData.getLeadId(),
                campaignData.getBusinessId(),
                campaignData.getCampaignId(),
                twilioData
        );
        log.info("Calling for leadId - {}, lead name - {}, businessId - {}, campaignRunId - {}, callType - {}",
                leadData.getLeadId(),leadData.getLeadName(), campaignRunData.getBusinessId(), campaignRunData.getCampaignRunId(), callType);
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
        String fileName = getInitialMessageRecordingFileName(leadData, campaignData, agentData, llmData);
        return this.callLogService.createCallLog(callType,
                campaignRunData.getCampaignRunId(),
                leadData.getLeadId(),  call.getSid(),
                twilioData.getBusinessNumber(), leadData.getLeadPhone(), fileName );

    }

    public String getInitialMessageRecordingFileName(LeadData leadData, CampaignData campaignData,  AgentData agentData, LlmData llmData) throws IOException {
        String initialMessage = campaignData.getFirstMessage();
        //replace placeholders with actual values
        initialMessage = initialMessage.replace("[Lead Name]", leadData.getLeadName())
                .replace("[Agent Name]", agentData.getAgentName());
        byte[] twilioAudio = TranscriptionUtils
                .generateFriendlyOpenAIAudioForTwilio(initialMessage, llmData, agentData.getVoice());
        // create UUID using leadId, campaignRunId and callType
        String uuid = UUID.randomUUID().toString();
        String fileName = "initial-message-" + uuid + ".wav";
        // Save to file for Twilio playback
        TranscriptionUtils
                .saveTwilioAudioToFile(twilioAudio, fileName);
        return fileName;
    }

    public String callTool(FunctionCallDto functionCallDto, TwilioStartEventDto twilioStartEventDto) {
        return this.toolsService.executeTool(functionCallDto.getName(),functionCallDto,twilioStartEventDto);
    }
    public String incomingCall(String host,String audioUrl,int campaignRunId,
                               String authToken,int businessId, int leadId, String callType )  {
        //TODO decrypt the auth token
        log.info("Incoming call request received for leadId - {}, businessId - {}, campaignRunId - {}, callType - {}",
                leadId, businessId, campaignRunId, callType);

        audioUrl = "https://" + audioUrl;
        log.info("Incoming call request received for audioUrl -{}",
                audioUrl);
        String url = "wss://"+host+"/api/call/media-stream";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>" +
                "<Response>" +
                "<Play>" + audioUrl + "</Play>" + // This line plays the audio
                "<Connect>" +
                "<Stream url=\"" + url + "\">" +
                "<Parameter name=\"authToken\" value=\"" + authToken + "\" />" +
                "<Parameter name=\"campaignRunId\" value=\"" + campaignRunId + "\" />" +
                "<Parameter name=\"businessId\" value=\"" + businessId + "\" />" +
                "<Parameter name=\"leadId\" value=\"" + leadId + "\" />" +
                "<Parameter name=\"callType\" value=\"" + callType + "\" />" +
                "</Stream>" +
                "</Connect>" +
                "</Response>";

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
                                   CampaignData campaignData,AgentData agentData) throws IOException, InterruptedException {
        CallLog callLog = getCallLog(twilioStartEventDto.
                        getTwilioStartMediaMessage().getStart().getCustomParameters().getCallType(),
                twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getCampaignRunId(),
                twilioStartEventDto.getTwilioStartMediaMessage().getStart().getCustomParameters().getLeadId()
        );
        systemMessage +="### Call log id\n" +
                callLog.getCallLogId();
        log.info("twilio start event Received - {}", twilioStartEventDto);
        OpenAiRequestDto openAiRequestDto = createOpenAiInit(twilioStartEventDto.getTwilioStartMediaMessage(), systemMessage, agentData);
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

    private OpenAiRequestDto createOpenAiInit(TwilioStartMessageDto twilioStartMessageDto, String systemMessage,AgentData agentData) {
        OpenAiRequestDto openAiRequestDto = new OpenAiRequestDto();
        openAiRequestDto.setEvent_id(twilioStartMessageDto.getStreamSid());
        openAiRequestDto.setType("session.update");
        OpenAiRequestDto.OpenAISession openAiSession = getOpenAISession(systemMessage,agentData.getVoice());
        openAiRequestDto.setSession(openAiSession);
        log.info("Created initial open ai request - {}",openAiRequestDto);
        return openAiRequestDto;
    }

    private OpenAiRequestDto.OpenAISession getOpenAISession(String systemMessage,String voice) {
        OpenAiRequestDto.OpenAISession openAiSession = new OpenAiRequestDto.OpenAISession();
        openAiSession.setInput_audio_format("g711_ulaw");
        openAiSession.setOutput_audio_format("g711_ulaw");
        openAiSession.setVoice(voice);
        openAiSession.setInstructions(systemMessage);
        openAiSession.setTool_choice("auto");
        openAiSession.setTemperature(0.6);
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
        return "### Agent Id\n" + agentData.getAgentId() + "\n" +
                        "### Agent Name\n" + agentData.getAgentName() + "\n" +
                        "### Role\n" + agentData.getRole() + "\n" +
                        "### Persona\n" + agentData.getPersona() + "\n" +
                        "### Conversation Guidelines\n" + campaignData.getConversationGuideLines() + "\n" +
                        "### First Message\n" + campaignData.getFirstMessage() + "\n" +
                        "The first message has already been spoken via Whisper. Do not repeat it or reintroduce yourself. " +
                        "From this point onward, take over the conversation naturally. " +
                        "After every sentence or idea expressed, incorporate a short natural pause (around 1–2 seconds) to allow the customer time to respond. " +
                        "If the customer begins speaking, immediately pause and listen without interruption. " +
                        "Use genuine backfill phrases like 'hmm', 'ahh', or 'I see' during these pauses to show active listening. " +
                        "Never assume the customer’s answer if they remain silent—instead, ask gently for clarification. For example, you might say, " +
                        "'I noticed you haven’t shared your thoughts yet. Could you tell me more about what you feel?' " +
                        "If no response is received, guide the conversation by asking an open-ended question or by shifting the topic gracefully, " +
                        "using phrases such as 'What are your thoughts on this?' or 'Let me know if you'd like to explore another topic.' " +
                        "Keep the tone warm, patient, and engaging, as if you’re conversing with a friend. " +
                        "Avoid sounding scripted or robotic by varying your phrasing and using natural transitional statements like 'Alright…', 'Makes sense…', or 'Let's take a moment to think about that.' " +
                        "If at any point the customer says 'bye' or signals a desire to end the conversation, respond with a polite farewell and execute disconnect_call(). " +
                        "Do not ask for personal details like WhatsApp or email unless they are volunteered; if provided, confirm clearly before calling update_lead_data(). " +
                        "Maintain an adaptive conversation flow: if the customer is silent for too long, either gently prompt them or, if appropriate, redirect to another subject or close the conversation gracefully.\n" +
                        "### Business Id\n" + businessData.getBusinessId() + "\n" +
                        "### Company Details\n" + businessData.getBusinessName() + "\n" +
                        "### Business Activity Description\n" + businessData.getBasicActivityDescription() + "\n" +
                        "### Campaign Id\n" + campaignData.getCampaignId() + "\n" +
                        "### Campaign Name\n" + campaignData.getCampaignName() + "\n" +
                        "### Campaign Desc\n" + campaignData.getCampaignDesc() + "\n" +
                        "### Campaign Prompt\n" + campaignData.getCampaignPrompt() + "\n" +
                        "### Campaign Aim\n" + campaignData.getCampaignAim() + "\n" +
                        "### Language of conversation\n" + campaignRunData.getLanguage() + "\n" +
                        "### Campaign Run Id\n" + campaignRunData.getCampaignRunId() + "\n" +
                        "### Handling FAQs\n" +
                        "For any questions, use get_metadata(\"<natural language query>\") to fetch answers. " +
                        "If needed, follow up with disconnect_call() using the returned streamId.\n" +
                        "### Handling Transcribed Contact Info\n" +
                        "If the customer shares a contact detail (number or email) via voice, repeat it back for confirmation. " +
                        "Only upon clear affirmation, call update_lead_data(). For emails, convert spoken 'at' into '@' appropriately.\n";
    }





}
