package com.dalaillama.content.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;

import java.util.Date;

@Data
public class LlmDto {

    private int llmId;

    private String label;

    private String value;

    private int creatorId;

    private Date createDate;

    private Date updateDate;

    private boolean active;

    @JsonIgnore
    private String llmToken;
}
