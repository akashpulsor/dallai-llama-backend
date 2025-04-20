package org.springframework.boot.crm.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class SpeakerDiarization {


    private static final HttpClient httpClient = HttpClient.newHttpClient();
    private static final ObjectMapper objectMapper = new ObjectMapper();
    static class TranscriptionResult {
        String text;
        List<Segment> segments;

        static class Segment {
            int id;
            double start;
            double end;
            String text;
            List<Word> words;

            static class Word {
                String word;
                double start;
                double end;
            }
        }
    }

    public static String getTranscription(byte[] audioData) throws IOException, InterruptedException, UnsupportedAudioFileException {
        TranscriptionResult transcriptionResult =transcribeWithOpenAI(audioData);

        // Extract audio features for speaker classification
        List<AudioSegment> segments = segmentAudio(audioData, transcriptionResult);

        // Identify speakers based on audio characteristics
        identifySpeakers(segments);

        // Format output with speaker identification
        String formattedTranscript = formatTranscript(segments);
        System.out.println(formattedTranscript);

        return formattedTranscript;
    }
    /**
     * Class to represent an audio segment with speaker info
     */
    static class AudioSegment {
        String text;
        double start;
        double end;
        double[] audioFeatures;
        int speakerId = -1;  // Will be assigned during clustering
    }

    /**
     * Get detailed transcription from OpenAI with timestamps
     */
    private static TranscriptionResult transcribeWithOpenAI(byte[] audioData) throws IOException {
        // Create audio resource
        String WHISPER_API_URL = "https://api.openai.com/v1/audio/transcriptions";
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.set("Authorization", "Bearer " + "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");

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
        body.add("response_format", "verbose_json");
        body.add("timestamp_granularities", "[\"word\", \"segment\"]");

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
            ObjectMapper objectMapper = new ObjectMapper();
            JsonNode jsonResponse = objectMapper.readTree(response.getBody());

            // Parse the response
            TranscriptionResult result = new TranscriptionResult();
            result.text = jsonResponse.get("text").asText();
            result.segments = new ArrayList<>();

            // Parse segments and words with timestamps
            JsonNode segments = jsonResponse.get("segments");
            if (segments != null && segments.isArray()) {
                for (JsonNode segment : segments) {
                    TranscriptionResult.Segment seg = new TranscriptionResult.Segment();
                    seg.id = segment.get("id").asInt();
                    seg.start = segment.get("start").asDouble();
                    seg.end = segment.get("end").asDouble();
                    seg.text = segment.get("text").asText();
                    seg.words = new ArrayList<>();

                    JsonNode words = segment.get("words");
                    if (words != null && words.isArray()) {
                        for (JsonNode word : words) {
                            TranscriptionResult.Segment.Word w = new TranscriptionResult.Segment.Word();
                            w.word = word.get("word").asText();
                            w.start = word.get("start").asDouble();
                            w.end = word.get("end").asDouble();
                            seg.words.add(w);
                        }
                    }

                    result.segments.add(seg);
                }
            }

            return result;
        } else {
            throw new RuntimeException("Whisper API error: " + response);
        }
    }
    /**
     * Create audio segments based on natural pauses in speech
     */
    private static List<AudioSegment> segmentAudio(byte[] audioData, TranscriptionResult transcription)
            throws UnsupportedAudioFileException, IOException {
        List<AudioSegment> segments = new ArrayList<>();

        // Use natural pauses in speech (silence detection) to create segments
        double minPauseDuration = 0.7;  // Minimum pause duration to consider a speaker change
        double currentStart = transcription.segments.get(0).start;
        double lastEnd = 0;
        StringBuilder currentText = new StringBuilder();

        for (TranscriptionResult.Segment segment : transcription.segments) {
            // Check if there's a significant pause between this segment and the last one
            if (segment.start - lastEnd > minPauseDuration && lastEnd > 0) {
                // Create a new segment
                AudioSegment audioSegment = new AudioSegment();
                audioSegment.start = currentStart;
                audioSegment.end = lastEnd;
                audioSegment.text = currentText.toString().trim();
                audioSegment.audioFeatures = extractAudioFeatures(audioData, currentStart, lastEnd);
                segments.add(audioSegment);

                // Start a new segment
                currentStart = segment.start;
                currentText = new StringBuilder();
            }

            currentText.append(segment.text).append(" ");
            lastEnd = segment.end;
        }

        // Add the final segment
        if (currentText.length() > 0) {
            AudioSegment audioSegment = new AudioSegment();
            audioSegment.start = currentStart;
            audioSegment.end = lastEnd;
            audioSegment.text = currentText.toString().trim();
            audioSegment.audioFeatures = extractAudioFeatures(audioData, currentStart, lastEnd);
            segments.add(audioSegment);
        }

        return segments;
    }

    /**
     * Extract audio features for speaker identification
     * In a real implementation, you would extract MFCC or spectral features
     */
    private static double[] extractAudioFeatures(byte[] audioData, double startTime, double endTime)
            throws UnsupportedAudioFileException, IOException {
        // This is a simplified implementation
        // In a real-world scenario, you would use libraries like TarsosDSP or JLibrosa
        // to extract meaningful audio features like MFCC, pitch, energy, etc.

        // For demonstration purposes, we'll create a simple feature vector
        // based on audio properties that might differentiate speakers

        ByteArrayInputStream byteArrayInputStream = new ByteArrayInputStream(audioData);
        AudioInputStream audioInputStream = AudioSystem.getAudioInputStream(byteArrayInputStream);
        AudioFormat format = audioInputStream.getFormat();


        // Calculate approximate byte position in the stream
        long startByte = (long) (startTime * format.getFrameRate() * format.getFrameSize());
        long endByte = (long) (endTime * format.getFrameRate() * format.getFrameSize());
        long bytesToRead = endByte - startByte;

        // Skip to start position
        audioInputStream.skip(startByte);

        // Read audio data
        byte[] data = new byte[(int) bytesToRead];
        audioInputStream.read(data);

        // Extract simple features - this is highly simplified
        // Real speaker diarization would use more sophisticated features

        // Feature 1: Average amplitude (volume)
        double avgAmplitude = calculateAvgAmplitude(data, format);

        // Feature 2: Zero crossing rate (rough indicator of pitch/frequency)
        double zeroCrossingRate = calculateZeroCrossingRate(data, format);

        // Feature 3: Spectral centroid (rough indicator of voice "brightness")
        double spectralCentroid = calculateSpectralCentroid(data, format);

        return new double[] { avgAmplitude, zeroCrossingRate, spectralCentroid };
    }

    /**
     * Calculate average amplitude of audio segment
     */
    private static double calculateAvgAmplitude(byte[] audioData, AudioFormat format) {
        double sum = 0;

        if (format.getSampleSizeInBits() == 16) {
            for (int i = 0; i < audioData.length; i += 2) {
                int sample = ((audioData[i+1] & 0xff) << 8) | (audioData[i] & 0xff);
                sum += Math.abs(sample);
            }
            return sum / (audioData.length / 2);
        } else {
            // 8-bit audio
            for (byte b : audioData) {
                sum += Math.abs(b);
            }
            return sum / audioData.length;
        }
    }

    /**
     * Calculate zero crossing rate
     */
    private static double calculateZeroCrossingRate(byte[] audioData, AudioFormat format) {
        int crossings = 0;
        boolean isPositive = false;
        boolean wasPositive = false;

        if (format.getSampleSizeInBits() == 16) {
            for (int i = 0; i < audioData.length; i += 2) {
                int sample = ((audioData[i+1] & 0xff) << 8) | (audioData[i] & 0xff);
                isPositive = sample >= 0;
                if (i > 0 && isPositive != wasPositive) {
                    crossings++;
                }
                wasPositive = isPositive;
            }
            return (double) crossings / (audioData.length / 2);
        } else {
            // 8-bit audio
            for (byte b : audioData) {
                isPositive = b >= 0;
                if (isPositive != wasPositive) {
                    crossings++;
                }
                wasPositive = isPositive;
            }
            return (double) crossings / audioData.length;
        }
    }

    /**
     * Calculate simplified spectral centroid
     */
    private static double calculateSpectralCentroid(byte[] audioData, AudioFormat format) {
        // This is a very simplified version
        // A real implementation would use FFT to calculate the spectral centroid

        // For demonstration purposes, we'll just return a rough approximation
        double zcr = calculateZeroCrossingRate(audioData, format);
        double amplitude = calculateAvgAmplitude(audioData, format);

        return zcr * amplitude * 0.5;
    }

    /**
     * Identify speakers using K-means clustering on audio features
     */
    private static void identifySpeakers(List<AudioSegment> segments) {
        // Simple K-means clustering for speaker identification
        int k = estimateSpeakerCount(segments);  // Number of speakers
        double[][] features = new double[segments.size()][];

        // Extract features for clustering
        for (int i = 0; i < segments.size(); i++) {
            features[i] = segments.get(i).audioFeatures;
        }

        // Perform K-means clustering
        int[] clusterAssignments = kMeansClustering(features, k);

        // Assign speaker IDs to segments
        for (int i = 0; i < segments.size(); i++) {
            segments.get(i).speakerId = clusterAssignments[i];
        }

        // Post-process to apply speaker continuity constraints
        smoothSpeakerAssignments(segments);
    }

    /**
     * Estimate number of speakers in the audio
     */
    private static int estimateSpeakerCount(List<AudioSegment> segments) {
        // In a real implementation, you could use techniques like BIC (Bayesian Information Criterion)
        // or silhouette analysis to estimate the optimal number of clusters

        // For simplicity, we'll just return a default value
        // You could make this configurable based on your expected use case
        // or use a heuristic based on the audio length
        return 2;  // Assume two speakers by default
    }

    /**
     * Simple K-means clustering implementation
     */
    private static int[] kMeansClustering(double[][] features, int k) {
        int n = features.length;
        int d = features[0].length;
        int[] assignments = new int[n];
        double[][] centroids = new double[k][d];

        // Initialize centroids with random data points
        for (int i = 0; i < k; i++) {
            int randomIndex = (int) (Math.random() * n);
            System.arraycopy(features[randomIndex], 0, centroids[i], 0, d);
        }

        boolean changed = true;
        int maxIterations = 100;
        int iteration = 0;

        while (changed && iteration < maxIterations) {
            changed = false;
            iteration++;

            // Assign points to nearest centroid
            for (int i = 0; i < n; i++) {
                int nearestCentroid = findNearestCentroid(features[i], centroids);
                if (nearestCentroid != assignments[i]) {
                    assignments[i] = nearestCentroid;
                    changed = true;
                }
            }

            if (!changed) {
                break;
            }

            // Recalculate centroids
            double[][] newCentroids = new double[k][d];
            int[] counts = new int[k];

            for (int i = 0; i < n; i++) {
                int cluster = assignments[i];
                counts[cluster]++;
                for (int j = 0; j < d; j++) {
                    newCentroids[cluster][j] += features[i][j];
                }
            }

            for (int i = 0; i < k; i++) {
                if (counts[i] > 0) {
                    for (int j = 0; j < d; j++) {
                        centroids[i][j] = newCentroids[i][j] / counts[i];
                    }
                }
            }
        }

        return assignments;
    }

    /**
     * Find the nearest centroid to a data point
     */
    private static int findNearestCentroid(double[] point, double[][] centroids) {
        int nearest = 0;
        double minDistance = Double.MAX_VALUE;

        for (int i = 0; i < centroids.length; i++) {
            double distance = calculateEuclideanDistance(point, centroids[i]);
            if (distance < minDistance) {
                minDistance = distance;
                nearest = i;
            }
        }

        return nearest;
    }

    /**
     * Calculate Euclidean distance between two points
     */
    private static double calculateEuclideanDistance(double[] p1, double[] p2) {
        double sum = 0;
        for (int i = 0; i < p1.length; i++) {
            double diff = p1[i] - p2[i];
            sum += diff * diff;
        }
        return Math.sqrt(sum);
    }

    /**
     * Apply smoothing to speaker assignments to avoid rapid switching between speakers
     */
    private static void smoothSpeakerAssignments(List<AudioSegment> segments) {
        // Simple smoothing: prevent rapid speaker changes
        int smoothingWindow = 3;  // Consider this many segments for smoothing

        for (int i = 0; i < segments.size(); i++) {
            // Count occurrences of each speaker in the window
            Map<Integer, Integer> speakerCounts = new HashMap<>();

            int startIdx = Math.max(0, i - smoothingWindow);
            int endIdx = Math.min(segments.size() - 1, i + smoothingWindow);

            for (int j = startIdx; j <= endIdx; j++) {
                int speakerId = segments.get(j).speakerId;
                speakerCounts.put(speakerId, speakerCounts.getOrDefault(speakerId, 0) + 1);
            }

            // Find the most common speaker in the window
            int mostCommonSpeaker = -1;
            int maxCount = 0;

            for (Map.Entry<Integer, Integer> entry : speakerCounts.entrySet()) {
                if (entry.getValue() > maxCount) {
                    maxCount = entry.getValue();
                    mostCommonSpeaker = entry.getKey();
                }
            }

            // Update the speaker ID
            segments.get(i).speakerId = mostCommonSpeaker;
        }
    }

    /**
     * Format transcript with speaker identification
     */
    private static String formatTranscript(List<AudioSegment> segments) {
        StringBuilder result = new StringBuilder();
        int currentSpeaker = -1;
        StringBuilder currentUtterance = new StringBuilder();

        for (AudioSegment segment : segments) {
            if (segment.speakerId != currentSpeaker && currentSpeaker != -1) {
                // Speaker change, output the previous speaker's text
                result.append("Speaker ").append(currentSpeaker + 1).append(": ")
                        .append(currentUtterance.toString().trim()).append("\n\n");
                currentUtterance = new StringBuilder();
            }

            currentSpeaker = segment.speakerId;
            currentUtterance.append(segment.text).append(" ");
        }

        // Add the last utterance
        if (currentUtterance.length() > 0) {
            result.append("Speaker ").append(currentSpeaker + 1).append(": ")
                    .append(currentUtterance.toString().trim()).append("\n");
        }

        return result.toString();
    }

    /**
     * Alternative approach: use linguistic patterns and context for speaker detection
     */
    public static String identifySpeakersByContent(String transcription) {
        // This is a simplified implementation that attempts to identify speakers
        // based on linguistic patterns, conversation flow, and quotation marks

        StringBuilder result = new StringBuilder();

        // Look for explicit speaker indicators like "John: Hello" or "Mary said,"
        Pattern speakerPattern = Pattern.compile("([A-Z][a-z]+)\\s*[:>,]\\s*([^\n]+)");
        Matcher matcher = speakerPattern.matcher(transcription);

        int lastEnd = 0;
        while (matcher.find()) {
            String speaker = matcher.group(1);
            String text = matcher.group(2);

            result.append(speaker).append(": ").append(text).append("\n\n");
            lastEnd = matcher.end();
        }

        // If no explicit speakers were found, try to segment based on paragraph breaks
        if (lastEnd == 0) {
            String[] paragraphs = transcription.split("\n\n");
            int speakerId = 0;

            for (String paragraph : paragraphs) {
                if (!paragraph.trim().isEmpty()) {
                    result.append("Speaker ").append((speakerId % 2) + 1).append(": ")
                            .append(paragraph.trim()).append("\n\n");
                    speakerId++;
                }
            }
        }

        return result.toString();
    }
}
