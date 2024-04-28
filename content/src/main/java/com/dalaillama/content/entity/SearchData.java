package com.dalaillama.content.entity;

import com.dalaillama.content.dto.SearchResponse;
import jakarta.persistence.*;
import lombok.Data;

import java.util.Date;
@Data
@Entity
@Table(name="search_data")
public class SearchData {

    //private SearchResponse searchResponse;

    @Column(name="search_query")
    private String searchQuery;

    @Id
    @GeneratedValue(strategy= GenerationType.UUID)
    @Column(name="search_id")
    private String searchId;

    @Column(name="user_id")
    private String userId;

    @Column(name="create_date")
    private Date createDate;

    @Column(name="modified_date")
    private Date modifiedDate;

    @Column(name="llmId_date")
    private int llmId;
}
