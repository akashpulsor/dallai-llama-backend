package com.dalai.llama.pbx.core.esl;


import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Raw TCP socket client for FreeSWITCH Event Socket Layer (ESL).
 *
 * ESL protocol is text-based over TCP:
 *   - Client connects to FreeSWITCH:8021
 *   - FreeSWITCH sends "Content-Type: auth/request"
 *   - Client sends "auth {password}\n\n"
 *   - FreeSWITCH sends "Content-Type: command/reply\nReply-Text: +OK accepted\n\n"
 *   - Client sends commands like "api uuid_kill {uuid}\n\n"
 *   - Client sends "event plain CHANNEL_HANGUP CHANNEL_ANSWER\n\n" to subscribe
 *
 * This class handles the raw socket I/O. EslConnectionManager handles lifecycle.
 * EslCommandExecutor provides typed command methods. EslEventListener processes events.
 */
@Slf4j
public class EslClient {

    private Socket socket;
    private BufferedReader reader;
    private BufferedWriter writer;
    private final AtomicBoolean connected = new AtomicBoolean(false);

    /**
     * Connect and authenticate to FreeSWITCH ESL.
     */
    public void connect(String host, int port, String password, int timeoutMs) throws IOException {
        log.info("ESL connecting to {}:{}", host, port);

        socket = new Socket();
        socket.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
        socket.setTcpNoDelay(true);
        socket.setKeepAlive(true);
        socket.setSoTimeout(0); // Blocking reads for event loop

        reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
        writer = new BufferedWriter(new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8));

        // Read auth/request
        String authRequest = readResponse();
        if (!authRequest.contains("auth/request")) {
            throw new IOException("Expected auth/request, got: " + authRequest);
        }

        // Authenticate
        sendRaw("auth " + password);
        String authReply = readResponse();
        if (!authReply.contains("+OK")) {
            throw new IOException("ESL auth failed: " + authReply);
        }

        connected.set(true);
        log.info("ESL connected and authenticated to {}:{}", host, port);
    }

    /**
     * Send an ESL API command and return the response body.
     * Example: sendApi("uuid_kill", "abc-123-def") → "+OK"
     */
    public String sendApi(String command, String args) throws IOException {
        ensureConnected();
        String cmd = args != null && !args.isEmpty()
                ? "api " + command + " " + args
                : "api " + command;
        sendRaw(cmd);
        return readResponse();
    }

    /**
     * Send a bgapi command (asynchronous — FreeSWITCH replies with Job-UUID).
     * Used for long-running operations like originate.
     */
    public String sendBgApi(String command, String args) throws IOException {
        ensureConnected();
        String cmd = args != null && !args.isEmpty()
                ? "bgapi " + command + " " + args
                : "bgapi " + command;
        sendRaw(cmd);
        return readResponse();
    }

    /**
     * Subscribe to FreeSWITCH events.
     * Example: subscribeEvents("CHANNEL_CREATE CHANNEL_ANSWER CHANNEL_HANGUP DTMF")
     */
    public void subscribeEvents(String eventTypes) throws IOException {
        ensureConnected();
        sendRaw("event plain " + eventTypes);
        String reply = readResponse();
        if (!reply.contains("+OK")) {
            log.warn("Event subscription response: {}", reply);
        }
        log.info("ESL subscribed to events: {}", eventTypes);
    }

    /**
     * Read the next complete ESL message (headers + body).
     * ESL messages are separated by double newline.
     * If Content-Length is present, reads that many bytes for the body.
     */
    public String readResponse() throws IOException {
        StringBuilder headers = new StringBuilder();
        String line;
        int contentLength = 0;

        // Read headers until empty line
        while ((line = reader.readLine()) != null) {
            if (line.isEmpty()) break;
            headers.append(line).append("\n");
            if (line.startsWith("Content-Length:")) {
                contentLength = Integer.parseInt(line.substring(15).trim());
            }
        }

        // Read body if Content-Length present
        if (contentLength > 0) {
            char[] body = new char[contentLength];
            int totalRead = 0;
            while (totalRead < contentLength) {
                int read = reader.read(body, totalRead, contentLength - totalRead);
                if (read == -1) throw new IOException("ESL stream ended mid-body");
                totalRead += read;
            }
            return headers.toString() + "\n" + new String(body);
        }

        return headers.toString();
    }

    /**
     * Read the next ESL event (blocking). Used by EslEventListener in its event loop.
     */
    public String readEvent() throws IOException {
        return readResponse();
    }

    public boolean isConnected() {
        return connected.get() && socket != null && !socket.isClosed();
    }

    public void disconnect() {
        connected.set(false);
        try {
            if (writer != null) writer.close();
            if (reader != null) reader.close();
            if (socket != null) socket.close();
        } catch (IOException e) {
            log.warn("Error closing ESL connection: {}", e.getMessage());
        }
        log.info("ESL disconnected");
    }

    // ── Internal ──

    private void sendRaw(String command) throws IOException {
        writer.write(command + "\n\n");
        writer.flush();
    }

    private void ensureConnected() throws IOException {
        if (!isConnected()) throw new IOException("ESL not connected");
    }
}