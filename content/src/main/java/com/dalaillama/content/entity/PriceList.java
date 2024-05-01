package com.dalaillama.content.entity;


import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name="price_list")
public class PriceList {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="price_id")
    private int priceId;

    @Column(name="llm_id")
    private int llmId;

    @Column(name="tool_id")
    private int toolId;

    @Column(name="price")
    private int price;

    @Column(name="credit")
    private  int credit;

    @Column(name="currency_id")
    private String currencyId;

    @Column(name="currency_symbol")
    private String currencySymbol;
}
