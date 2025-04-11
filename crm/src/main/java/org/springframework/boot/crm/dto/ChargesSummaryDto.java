package org.springframework.boot.crm.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ChargesSummaryDto {

    private double totalCharges;
    private double totalServiceCharges;
    private double totalModelCharges;
    private double totalCarrierCharges;
    private double totalEffectiveCost;
    private int id;



}
