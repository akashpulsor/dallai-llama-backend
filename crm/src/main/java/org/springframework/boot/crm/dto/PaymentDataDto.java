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

    public PaymentDataDto(int id) {
        this.id = this.id;
        this.totalCharges = 0;
        this.totalServiceCharges = 0;
        this.totalModelCharges = 0;
        this.totalCarrierCharges = 0;
        this.totalEffectiveCost = 0;
        this.totalInputToken = 0;
        this.totalOutputToken = 0;
        this.totalToken = 0;
        this.totalCallTime = 0;
        this.totalInputTextToken = 0;
        this.totalInputAudioToken = 0;
        this.totalInputCachedToken = 0;
        this.totalInputCachedTextToken = 0;
        this.totalInputCachedAudioToken = 0;
        this.totalOutputTextToken = 0;
        this.totalOutputAudioToken = 0;
    }
}
