package com.dalai.llama.tenant.domain.event;

import lombok.*;

@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WalletExternalEvent {
    private String eventType;
    private Object data;
}
