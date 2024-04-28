package com.dalaillama.content.entity;


//import javax.persistence.*;
import jakarta.persistence.*;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;

import java.util.Date;

@Data
@Entity
@Table(name="tools")
public class Tools {
/*
* {
        "id": 1,
        "name": "llamaContent",
        "title": "llama-content",
        "creatorId": 1,
        "creatorName": "Akash",
        "createDate": "24-01-1989",
        "updateDate": "24-01-1989",
        "active": true
    },
*
* */
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="tool_id")
    private int toolId;

    @Column(name="name")
    private String name;

    @Column(name="title")
    private String title;

    @Column(name="creator_id")
    private int creatorId;

    @Column(name="creator_name")
    private String creatorName;

    @CreatedDate
    @Column(name="create_date")
    private Date createDate;

    @LastModifiedDate
    @Column(name="update_date")
    private Date updateDate;

    @Column(name="active")
    private boolean active;
}
