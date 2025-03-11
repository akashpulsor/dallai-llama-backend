package org.springframework.boot.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.*;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.Arrays;
import java.util.HashSet;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;

@Slf4j
public class RealTimeSession {
    private WebSocket webSocket;
    private CountDownLatch latch;
    private final ApplicationEventPublisher applicationEventPublisher;
    private WebSocketSession twilioSession;

    private final TwilioStartEventDto twilioStartEventDto;

    private final LlmData llmData;

    public RealTimeSession(String json, TwilioStartEventDto twilioStartEventDto, ApplicationEventPublisher applicationEventPublisher,LlmData llmData) throws InterruptedException, JsonProcessingException {
        this.applicationEventPublisher=applicationEventPublisher;
        this.twilioStartEventDto=twilioStartEventDto;
        this.llmData=llmData;
        connect(json, applicationEventPublisher, twilioStartEventDto, llmData);
    }

    public WebSocket getWebSocket(){
        return this.webSocket;
    }

    public TwilioStartEventDto getTwilioStartEventDto(){
        return this.twilioStartEventDto;
    }


    private void connect(String json, ApplicationEventPublisher applicationEventPublisher, TwilioStartEventDto twilioStartEventDto,LlmData llmData) throws InterruptedException, JsonProcessingException {
        this.latch = new CountDownLatch(1);
        String OPENAI_API_KEY = llmData.getApiKey();

        WebSocket ws = HttpClient
                .newHttpClient()
                .newWebSocketBuilder().header("Authorization", "Bearer " + OPENAI_API_KEY).
                header("OpenAI-Beta", "realtime=v1")
                .buildAsync(URI.create("wss://api.openai.com/v1/realtime?model=gpt-4o-realtime-preview-2024-10-01"), new WebSocketClient(latch, applicationEventPublisher,twilioStartEventDto, json))
                .join();
        this.webSocket = ws;

        latch.await();
    }






    private static class WebSocketClient implements WebSocket.Listener {
        private final CountDownLatch latch;
        private  final ApplicationEventPublisher applicationEventPublisher;
        private final TwilioStartEventDto twilioStartEventDto;
        private final String json;

        public WebSocketClient(CountDownLatch latch, ApplicationEventPublisher applicationEventPublisher, TwilioStartEventDto twilioStartEventDto, String json) {
            this.latch = latch;
            this.applicationEventPublisher = applicationEventPublisher;
            this.twilioStartEventDto = twilioStartEventDto;
            this.json = json;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            log.info("onOpen using sub protocol -{}" , webSocket.getSubprotocol());
            WebSocket.Listener.super.onOpen(webSocket);
            webSocket.sendText(json, true);
        }

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            System.out.println("onText received " + data);
            handleTextMessage(new String(data.toString()));
            latch.countDown();
            return WebSocket.Listener.super.onText(webSocket, data, last);
        }

        @Override
        public void onError(WebSocket webSocket, Throwable error) {
            System.out.println("Bad day! " + webSocket.toString());
            log.error("Websocket connection on open ai failed", error);
            WebSocket.Listener.super.onError(webSocket, error);
        }

        @Override
        public  CompletionStage<?> onClose(WebSocket webSocket,
                                           int statusCode,
                                           String reason) {
            latch.countDown();
            log.info("Open Ai Session is getting closed due to -{}", reason );

            return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
        }

        protected void handleTextMessage(String message)  {
            log.info("ABCD - {}", message);
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
            try {
                OpenAiResponseDto openAiResponse = objectMapper.readValue(message, OpenAiResponseDto.class);
                if (openAiResponse.getType().equals("session.created")) {
                    OpenAiSessionCreateEvent sessionCreateEvent = new OpenAiSessionCreateEvent(this, openAiResponse);
                    this.applicationEventPublisher.publishEvent(sessionCreateEvent);
                    //log.info("Session updated successfully: {}", sessionCreateEvent);
                }
                else if (openAiResponse.getType().equals("session.updated")) {
                    OpenAiSessionUpdateEvent sessionUpdateEvent = new OpenAiSessionUpdateEvent(this, openAiResponse);
                    this.applicationEventPublisher.publishEvent(sessionUpdateEvent);
                    //log.info("Session updated successfully: {}", openAiResponse);
                }
                else if (openAiResponse.getType().equals("response.done")) {
                    OpenAiResponseDoneDto responseDoneDto = objectMapper.readValue(message, OpenAiResponseDoneDto.class);
                    OpenAiResponseDoneEvent openAiResponseDoneEvent = new OpenAiResponseDoneEvent(this, responseDoneDto, twilioStartEventDto);
                    this.applicationEventPublisher.publishEvent(openAiResponseDoneEvent);
                    //log.info("Response done : {}", responseDoneDto);
                }
                else if (openAiResponse.getType().equals("response.function_call_arguments.done")) {
                    log.info("Function name called: {}", message);
                    FunctionCallDto responseDoneDto = objectMapper.readValue(message, FunctionCallDto.class);
                    FunctionCallEvent functionCallEvent = new FunctionCallEvent(this, responseDoneDto, twilioStartEventDto);
                    this.applicationEventPublisher.publishEvent(functionCallEvent);

                }
                else if (openAiResponse.getType().equals("response.audio.delta")) {
                    OpenAiAudioDto openAiAudioDto = objectMapper.readValue(message, OpenAiAudioDto.class);
                    OpenAiAudioEvent openAiAudioEvent = new OpenAiAudioEvent(this, openAiAudioDto,this.twilioStartEventDto);
                    this.applicationEventPublisher.publishEvent(openAiAudioEvent);
                    //log.info("response.audio.delta successfully: {}", openAiResponse);
                }
                else if (getLogEventTypes().contains(openAiResponse.getType())) {
                    OpenAiEventDto openAiEventDto = new OpenAiEventDto(this, openAiResponse);
                    this.applicationEventPublisher.publishEvent(openAiEventDto);
                    //log.info("Received event: {}", openAiResponse.getType());
                }
            } catch (JsonProcessingException e) {
                log.error("Failed while getting OpenAI response", e);
            }
        }



        private HashSet<String> getLogEventTypes() {

            return new HashSet<>(Arrays.asList(
                    "response.content.done",
                    "rate_limits.updated",
                    "response.function_call_arguments.done",
                    "response.done",
                    "input_audio_buffer.committed",
                    "input_audio_buffer.speech_stopped",
                    "input_audio_buffer.speech_started",
                    "session.created",
                    "function.call"
            ));
        }

    }
}
