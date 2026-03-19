package com.dalai.llama.pbx.core.domain.entity.kamailio;


import jakarta.persistence.*;
import lombok.*;
import java.time.Instant;

@Entity
@Table(name = "domain_attrs")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class DomainAttrs {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(length = 64)
    private String did;

    @Column(nullable = false, length = 32)
    private String name;

    @Column(nullable = false)
    @Builder.Default
    private Integer type = 0;

    @Column(nullable = false, length = 255)
    private String value;

    @Column(name = "last_modified")
    private Instant lastModified;

    @PrePersist
    void prePersist() { lastModified = Instant.now(); }
}