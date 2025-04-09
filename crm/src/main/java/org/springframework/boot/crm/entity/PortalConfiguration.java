package org.springframework.boot.crm.entity;

import jakarta.persistence.*;
import lombok.*;

import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "portal_configuration")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PortalConfiguration {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "portal_id")
    private Integer portalId;

    @Column(name = "portal_name", nullable = false, unique = true)
    private String portalName;

    @Column(name = "portal_description", nullable = false)
    private String portalDescription;

    @Column(name = "base_url", nullable = false)
    private String baseUrl;

    @Column(name = "user_name", nullable = false)
    private String userName;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "business_id", nullable = false)
    private int businessId;

    // One-to-Many relationship with Intent
    @OneToMany(mappedBy = "portalConfiguration",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    private List<Intent> intents = new ArrayList<>();

    // Helper method to add intent
    public void addIntent(Intent intent) {
        if(intents==null){
            intents = new ArrayList<>();
        }
        intents.add(intent);
        intent.setPortalConfiguration(this);
    }

    // Helper method to remove intent
    public void removeIntent(Intent intent) {
        intents.remove(intent);
        intent.setPortalConfiguration(null);
    }

    @Override
    public String toString() {
        return "PortalConfiguration{" +
                "portalId=" + portalId +
                ", portalName='" + portalName + '\'' +
                ", portalDescription='" + portalDescription + '\'' +
                ", baseUrl='" + baseUrl + '\'' +
                ", userName='" + userName + '\'' +
                ", password='" + (password != null ? password : null) + '\'' + // Masking password
                ", businessId=" + businessId +
                '}';
    }
}
