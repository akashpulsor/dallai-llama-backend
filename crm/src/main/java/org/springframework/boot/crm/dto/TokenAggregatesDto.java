package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class TokenAggregatesDto {

    private int id;
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
