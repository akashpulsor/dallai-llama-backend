package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class CallStatusDto {
    String callSid ;
    String callStatus ;
    String callDuration ;
    String timestamp ;
    String fromNumber ;
    String toNumber ;
    String direction ;
    String queueTime ;
    String streamSid ;
}
