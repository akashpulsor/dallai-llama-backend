package org.springframework.boot.crm.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.dto.OpenAiResponseDto;
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

    private static final String SERVER_URI = "ws://example.com/websocket"; // replace with your server URI
    private static final String AUTH_TOKEN = ""; // replace with your actual token
    private WebSocket webSocket;

    private String streamId;

    private CountDownLatch latch;
    private final ApplicationEventPublisher applicationEventPublisher;
    private WebSocketSession twilioSession;

    public RealTimeSession(String json, WebSocketSession twilioSession, String streamId, ApplicationEventPublisher applicationEventPublisher) throws InterruptedException, JsonProcessingException {
        this.streamId = streamId;
        this.twilioSession = twilioSession;
        this.applicationEventPublisher=applicationEventPublisher;
        //connect(json, applicationEventPublisher, streamId);
    }

    private void connect(String json, ApplicationEventPublisher applicationEventPublisher, String streamId) throws InterruptedException, JsonProcessingException {
        this.latch = new CountDownLatch(1);

        latch.await();
    }

    private static class WebSocketClient implements WebSocket.Listener {
        private final CountDownLatch latch;
        private  final ApplicationEventPublisher applicationEventPublisher;
        private final String streamId;
        private final String json;

        public WebSocketClient(CountDownLatch latch, ApplicationEventPublisher applicationEventPublisher, String streamId, String json) {
            this.latch = latch;
            this.applicationEventPublisher = applicationEventPublisher;
            this.streamId = streamId;
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
            log.info("AKASH OPEN AI: " + message);

        }


        private HashSet<String> getLogEventTypes() {

            return new HashSet<>(Arrays.asList(
                    "response.content.done",
                    "rate_limits.updated",
                    "response.done",
                    "input_audio_buffer.committed",
                    "input_audio_buffer.speech_stopped",
                    "input_audio_buffer.speech_started",
                    "session.created"
            ));
        }
    }
}
