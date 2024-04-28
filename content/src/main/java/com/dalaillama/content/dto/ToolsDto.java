package com.dalaillama.content.dto;


import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.util.Date;

@Data
public class ToolsDto {

    private int toolId;
    private String name;
    private String title;
    private int creatorId;
    private String creatorName;
    private Date createDate;
    private Date updateDate;
    private boolean active;
}
