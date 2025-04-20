package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class PaymentDataDto {

    private int id;
    private double totalCharges;
    private double totalServiceCharges;
    private double totalModelCharges;
    private double totalCarrierCharges;
    private double totalEffectiveCost;
    private long totalInputToken;
    private long totalOutputToken;
    private long totalToken;
    private long totalCallTime;
    private long totalInputTextToken;
    private long totalInputAudioToken;
    private long totalInputCachedToken;
    private long totalInputCachedTextToken;
    private long totalInputCachedAudioToken;
    private long totalOutputTextToken;
    private long totalOutputAudioToken;
    private String inBoundText;
    private int transcriptionId;

    public PaymentDataDto(int id) {
        this.id = this.id;

    }

    public PaymentDataDto(     int id,
     double totalCharges,
     double totalServiceCharges,
     double totalModelCharges,
     double totalCarrierCharges,
     double totalEffectiveCost,
     long totalInputToken,
     long totalOutputToken,
     long totalToken,
     long totalCallTime,
     long totalInputTextToken,
     long totalInputAudioToken,
     long totalInputCachedToken,
     long totalInputCachedTextToken,
     long totalInputCachedAudioToken,
     long totalOutputTextToken,
     long totalOutputAudioToken){
        this.id = id;
        this.totalCharges = totalCharges;
        this.totalServiceCharges = totalServiceCharges;
        this.totalModelCharges = totalModelCharges;
        this.totalCarrierCharges = totalCarrierCharges;
        this.totalEffectiveCost = totalEffectiveCost;
        this.totalInputToken = totalInputToken;
        this.totalOutputToken = totalOutputToken;
        this.totalToken = totalToken;
        this.totalCallTime = totalCallTime;
        this.totalInputTextToken = totalInputTextToken;
        this.totalInputAudioToken = totalInputAudioToken;
        this.totalInputCachedToken = totalInputCachedToken;
        this.totalInputCachedTextToken = totalInputCachedTextToken;
        this.totalInputCachedAudioToken = totalInputCachedAudioToken;
        this.totalOutputTextToken = totalOutputTextToken;
        this.totalOutputAudioToken = totalOutputAudioToken;
    }
}
