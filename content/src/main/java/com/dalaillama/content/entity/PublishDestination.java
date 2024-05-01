package com.dalaillama.content.entity;

//import javax.persistence.*;
import jakarta.persistence.*;
import lombok.Data;

@Data
@Entity
@Table(name="publish_destination")
public class PublishDestination {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="publish_id")
    private int publishId;

    @Column(name="destination_id")
    private int destinationId;

    @Column(name="destination_name")
    private String destinationName;

    @Column(name="logo_url")
    private String logoUrl;

    @Column(name="user_name")
    private String userName;

    @Column(name="password")
    private String password;

}
