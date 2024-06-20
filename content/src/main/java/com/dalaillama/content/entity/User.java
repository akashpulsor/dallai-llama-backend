package com.dalaillama.content.entity;


import com.dalaillama.content.dto.Gender;
import jakarta.persistence.*;
import lombok.Data;

import java.util.HashSet;
import java.util.List;
import java.util.Set;


@Data
@Entity
@Table(name="user",
        uniqueConstraints = {
                @UniqueConstraint(columnNames = "user_name"),
                @UniqueConstraint(columnNames = "email")
        })
public class User {
    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="id")
    private int id;

    @Column(name="name")
    private String name;


    @Column(name="email")
    private String email;

    @Column(name="password")
    private String password;

    @Column(name="company_name")
    private String companyName;

    @Column(name="linkedIn_url")
    private String linkedInUrl;

    @Column(name="github_url")
    private String githubUrl;

    @Column(unique=true, name="phone_number")
    private String phoneNumber;


    @Column(name="account_non_expired")
    private boolean accountNonExpired;

    @Column(name="account_non_locked")
    private boolean accountNonLocked;

    @Column(name="credentials_non_expired")
    private boolean credentialsNonExpired;

    @Column(name="profile_image")
    private String profileImage;

    @Column(name="follower_count")
    private long followerCount;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();


    @Column(name="bio")
    private String bio;

    @Enumerated(EnumType.STRING)
    private Gender gender;

}
