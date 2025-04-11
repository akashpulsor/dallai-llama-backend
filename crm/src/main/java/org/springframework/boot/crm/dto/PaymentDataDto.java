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
    private int totalInputToken;
    private int totalOutputToken;
    private int totalToken;
    private int totalCallTime;
    private int totalInputTextToken;
    private int totalInputAudioToken;
    private int totalInputCachedToken;
    private int totalInputCachedTextToken;
    private int totalInputCachedAudioToken;
    private int totalOutputTextToken;
    private int totalOutputAudioToken;


}
