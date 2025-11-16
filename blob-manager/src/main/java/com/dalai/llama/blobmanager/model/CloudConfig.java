
package com.dalai.llama.blobmanager.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Column;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "cloud_config")
public class CloudConfig {
    @Id
    private String id = UUID.randomUUID().toString();
    private String name;
    private String provider;
    @Column(columnDefinition = "TEXT")
    private String props;
    private Instant createdAt = Instant.now();
    public CloudConfig() {}
    public String getId(){return id;} public void setId(String id){this.id=id;}
    public String getName(){return name;} public void setName(String name){this.name=name;}
    public String getProvider(){return provider;} public void setProvider(String provider){this.provider=provider;}
    public String getProps(){return props;} public void setProps(String props){this.props=props;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant createdAt){this.createdAt=createdAt;}
}
