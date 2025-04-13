package org.springframework.boot.crm.dto;

import lombok.*;

@Data
@AllArgsConstructor
@Getter
@Setter
@NoArgsConstructor
public class TokenAggregatesDto {

    private int id;
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



}
