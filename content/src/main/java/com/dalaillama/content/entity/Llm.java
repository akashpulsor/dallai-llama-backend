package com.dalaillama.content.entity;


import jakarta.persistence.*;
import lombok.Data;


import java.util.Date;
@Data
@Entity
@Table(name="llm")
public class Llm {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="llm_id")
    private int llmId;

    @Column(name="label")
    private String label;

    @Column(name="value")
    private String value;

    @Column(name="creator_id")
    private int creatorId;

    @Column(name="create_date")
    private Date createDate;

    @Column(name="update_date")
    private Date updateDate;

    @Column(name="active")
    private boolean active;

    @Column(name="llm_token")
    private String llmToken;
}
