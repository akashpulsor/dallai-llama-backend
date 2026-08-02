package com.dalai.llama.creator.dto.internal;

import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Typed boundary for extracting avatar speech from the full screenplay JSON.
 * Unknown planning fields are retained so the source context remains useful for
 * later evaluation or training exports.
 */
@JsonIgnoreProperties(ignoreUnknown = false)
public class AvatarScreenplayDocument {

    private String dialogueLanguage;
    private Object hook;
    private Object hooks;
    private Object retentionPlan;
    private Object storytellingPlan;
    private Object narrativeArc;
    private Object screenplay;
    private List<Shot> shots = new ArrayList<>();
    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public String getDialogueLanguage() {
        return dialogueLanguage;
    }

    public void setDialogueLanguage(String dialogueLanguage) {
        this.dialogueLanguage = dialogueLanguage;
    }

    public Object getHook() {
        return hook;
    }

    public void setHook(Object hook) {
        this.hook = hook;
    }

    public Object getHooks() {
        return hooks;
    }

    public void setHooks(Object hooks) {
        this.hooks = hooks;
    }

    public Object getRetentionPlan() {
        return retentionPlan;
    }

    public void setRetentionPlan(Object retentionPlan) {
        this.retentionPlan = retentionPlan;
    }

    public Object getStorytellingPlan() {
        return storytellingPlan;
    }

    public void setStorytellingPlan(Object storytellingPlan) {
        this.storytellingPlan = storytellingPlan;
    }

    public Object getNarrativeArc() {
        return narrativeArc;
    }

    public void setNarrativeArc(Object narrativeArc) {
        this.narrativeArc = narrativeArc;
    }

    public Object getScreenplay() {
        return screenplay;
    }

    public void setScreenplay(Object screenplay) {
        this.screenplay = screenplay;
    }

    public List<Shot> getShots() {
        return shots;
    }

    public void setShots(List<Shot> shots) {
        this.shots = shots == null ? new ArrayList<>() : shots;
    }

    public Map<String, Object> getAdditionalProperties() {
        return additionalProperties;
    }

    @JsonAnySetter
    public void putAdditionalProperty(String name, Object value) {
        additionalProperties.put(name, value);
    }

    @JsonIgnoreProperties(ignoreUnknown = false)
    public static class Shot {
        private Integer shotNumber;
        private Integer sceneNumber;
        private Integer sequenceNumber;
        private String title;
        private String voiceOver;
        private String voiceover;
        private String narration;
        private JsonNode dialogue;
        private JsonNode srtCues;
        private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

        public Integer getShotNumber() { return shotNumber; }
        public void setShotNumber(Integer shotNumber) { this.shotNumber = shotNumber; }
        public Integer getSceneNumber() { return sceneNumber; }
        public void setSceneNumber(Integer sceneNumber) { this.sceneNumber = sceneNumber; }
        public Integer getSequenceNumber() { return sequenceNumber; }
        public void setSequenceNumber(Integer sequenceNumber) { this.sequenceNumber = sequenceNumber; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public String getVoiceOver() { return voiceOver; }
        public void setVoiceOver(String voiceOver) { this.voiceOver = voiceOver; }
        public String getVoiceover() { return voiceover; }
        public void setVoiceover(String voiceover) { this.voiceover = voiceover; }
        public String getNarration() { return narration; }
        public void setNarration(String narration) { this.narration = narration; }
        public JsonNode getDialogue() { return dialogue; }
        public void setDialogue(JsonNode dialogue) { this.dialogue = dialogue; }
        public JsonNode getSrtCues() { return srtCues; }
        public void setSrtCues(JsonNode srtCues) { this.srtCues = srtCues; }
        public Map<String, Object> getAdditionalProperties() { return additionalProperties; }

        @JsonAnySetter
        public void putAdditionalProperty(String name, Object value) {
            additionalProperties.put(name, value);
        }
    }
}
