package org.springframework.boot.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.crm.entity.LlmData;
import org.springframework.boot.crm.entity.TwilioData;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

@Slf4j
public class TranscriptionUtils {

    public static String transcribeAudio(byte[] audioData, LlmData llmData) {
        try {
            String WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions";
            // Download audio from Twilio URL

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);
            headers.set("Authorization", "Bearer " + llmData.getApiKey());
            // Create audio resource
            ByteArrayResource audioResource = new ByteArrayResource(audioData) {
                @Override
                public String getFilename() {
                    return "audio.mp3";
                }
            };
            // Prepare request body
            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            body.add("model", "whisper-1");
            body.add("file", audioResource);
            // Create request entity
            HttpEntity<MultiValueMap<String, Object>> requestEntity =
                    new HttpEntity<>(body, headers);
            // Make API call
            ResponseEntity<String> response = new RestTemplate().exchange(
                    WHISPER_API_URL,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            if (response.getStatusCode() == HttpStatus.OK) {
                ObjectMapper mapper = new ObjectMapper();
                JsonNode root = mapper.readTree(response.getBody());
                return root.get("text").asText();
            } else {
                throw new RuntimeException("Whisper API error: " + response.getStatusCode());
            }

        } catch (Exception e) {
            log.error("Error transcribing audio: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to transcribe audio", e);
        }
    }

    public static byte[] downloadAudio(String url, TwilioData twilioData) {
        try {
            HttpHeaders headers = new HttpHeaders();
            String auth = twilioData.getAccountSid() + ":" + twilioData.getAccountAuthToken();
            String authHeader = "Basic " + java.util.Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.US_ASCII));
            headers.set("Authorization", authHeader);

            // Create HttpEntity with headers
            HttpEntity<String> entity = new HttpEntity<>(headers);

            // Use exchange method instead of getForEntity to include headers
            ResponseEntity<byte[]> response = new RestTemplate().exchange(
                    url,
                    HttpMethod.GET,
                    entity,
                    byte[].class
            );

            return response.getBody();
        } catch (Exception e) {
            log.error("Error downloading audio: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to download audio", e);
        }
    }

    /**
     * Transcribes Twilio media payload by handling format conversion and transcription
     */
    public static String transcribeTwilioPayload(String base64Payload, LlmData llmData) {
        try {
            // Decode base64 audio (mulaw format from Twilio)
            byte[] mulawAudio = java.util.Base64.getDecoder().decode(base64Payload);

            // Convert to WAV format
            byte[] wavAudio = convertTwilioAudioToWav(mulawAudio);

            // Use existing transcription method
            return transcribeAudio(wavAudio, llmData);

        } catch (Exception e) {
            log.error("Error transcribing Twilio payload: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to transcribe Twilio payload", e);
        }
    }

    /**
     * Converts Twilio mulaw audio to WAV format
     */
    private static byte[] convertTwilioAudioToWav(byte[] mulawData) {
        // Add minimum required padding (0.1 seconds at 8000Hz = 800 samples)
        int minSamples = 800;
        int paddedLength = Math.max(mulawData.length, minSamples);
        byte[] paddedMulaw = new byte[paddedLength];

        // Copy original data
        System.arraycopy(mulawData, 0, paddedMulaw, 0, mulawData.length);

        // Fill remaining with silence (0xFF in mulaw is silence)
        if (paddedLength > mulawData.length) {
            Arrays.fill(paddedMulaw, mulawData.length, paddedLength, (byte) 0xFF);
        }

        // Create WAV header
        byte[] wavHeader = createWavHeader(paddedLength);

        // Convert mulaw to PCM
        byte[] pcmData = new byte[paddedLength * 2];
        for (int i = 0; i < paddedLength; i++) {
            short pcm = MULAW_TO_PCM_TABLE[paddedMulaw[i] & 0xFF];
            pcmData[i * 2] = (byte) (pcm & 0xFF);
            pcmData[i * 2 + 1] = (byte) ((pcm >> 8) & 0xFF);
        }

        // Combine header and PCM data
        byte[] wavFile = new byte[wavHeader.length + pcmData.length];
        System.arraycopy(wavHeader, 0, wavFile, 0, wavHeader.length);
        System.arraycopy(pcmData, 0, wavFile, wavHeader.length, pcmData.length);

        return wavFile;
    }
    /**
     * Creates WAV header for Twilio audio format
     */
    private static byte[] createWavHeader(int pcmLength) {
        byte[] header = new byte[44];
        long totalDataLen = pcmLength * 2 + 36;
        long bitrate = SAMPLE_RATE * CHANNELS * BITS_PER_SAMPLE / 8;

        header[0] = 'R'; header[1] = 'I'; header[2] = 'F'; header[3] = 'F';
        header[4] = (byte) (totalDataLen & 0xff);
        header[5] = (byte) ((totalDataLen >> 8) & 0xff);
        header[6] = (byte) ((totalDataLen >> 16) & 0xff);
        header[7] = (byte) ((totalDataLen >> 24) & 0xff);
        header[8] = 'W'; header[9] = 'A'; header[10] = 'V'; header[11] = 'E';
        header[12] = 'f'; header[13] = 'm'; header[14] = 't'; header[15] = ' ';
        header[16] = 16;
        header[17] = 0;
        header[18] = 0;
        header[19] = 0;
        header[20] = 1; // PCM format
        header[21] = 0;
        header[22] = (byte) CHANNELS;
        header[23] = 0;
        header[24] = (byte) (SAMPLE_RATE & 0xff);
        header[25] = (byte) ((SAMPLE_RATE >> 8) & 0xff);
        header[26] = (byte) ((SAMPLE_RATE >> 16) & 0xff);
        header[27] = (byte) ((SAMPLE_RATE >> 24) & 0xff);
        header[28] = (byte) (bitrate & 0xff);
        header[29] = (byte) ((bitrate >> 8) & 0xff);
        header[30] = (byte) ((bitrate >> 16) & 0xff);
        header[31] = (byte) ((bitrate >> 24) & 0xff);
        header[32] = (byte) (CHANNELS * BITS_PER_SAMPLE / 8);
        header[33] = 0;
        header[34] = BITS_PER_SAMPLE;
        header[35] = 0;
        header[36] = 'd'; header[37] = 'a'; header[38] = 't'; header[39] = 'a';
        header[40] = (byte) ((pcmLength * 2) & 0xff);
        header[41] = (byte) ((pcmLength * 2 >> 8) & 0xff);
        header[42] = (byte) ((pcmLength * 2 >> 16) & 0xff);
        header[43] = (byte) ((pcmLength * 2 >> 24) & 0xff);

        return header;
    }

    private static final int SAMPLE_RATE = 8000; // Twilio's sample rate
    private static final int CHANNELS = 1; // Mono
    private static final int BITS_PER_SAMPLE = 16; // 16-bit PCM

    private static final short[] MULAW_TO_PCM_TABLE = initMulawTable();

    private static short[] initMulawTable() {
        short[] table = new short[256];
        for (int i = 0; i < 256; i++) {
            int mu = ~i;
            int sign = (mu & 0x80) >> 7;
            int exponent = (mu & 0x70) >> 4;
            int mantissa = mu & 0x0f;
            int magnitude = ((mantissa << 3) + 0x84) << exponent;
            table[i] = (short) (sign == 1 ? -magnitude : magnitude);
        }
        return table;
    }
}
