package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.util.HashSet;
import java.util.Set;

@Data
@Entity(name = "business_data")
public class BusinessData {

    @Id
    @GeneratedValue(strategy= GenerationType.IDENTITY)
    @Column(name="business_id")
    private int businessId;

    @Column(name="email")
    private String email;

    @Column(name="name")
    private String name;

    @Column(name="mobile")
    private String mobile;

    @Column(name="whatsapp_number")
    private String whatsAppNumber;

    @Column(name="business_name")
    private String businessName;

    @Column(name="country_code")
    private String countryCode;

    @Column(name="country_dialing_code")
    private String countryDialingCode;

    @Column(name="business_size_id")
    private int businessSizeId;

    @Column(name="password")
    private String password;

    @Column(name="basic_activity_description")
    private String basicActivityDescription;

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

    @Column(name="instagram_handle")
    private long instagramHandle;

    @Column(name="linkedIn_handle")
    private long linkedInHandle;

    @Column(name="is_active")
    private boolean isActive;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(name = "user_roles",
            joinColumns = @JoinColumn(name = "business_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id"))
    private Set<Role> roles = new HashSet<>();


    @Embedded
    private SanitaryColumn sanitaryColumn;

}
