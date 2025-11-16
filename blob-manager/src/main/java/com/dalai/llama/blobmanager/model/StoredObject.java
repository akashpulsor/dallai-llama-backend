
package com.dalai.llama.blobmanager.model;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import jakarta.persistence.Column;

@Entity
@Table(name = "stored_object")
public class StoredObject {
    @Id
    private String id = UUID.randomUUID().toString();
    private String tenantId;
    private String callId;
    @Column(length = 1024)
    private String objectKey;
    @Column(columnDefinition = "TEXT")
    private String url;
    private String backend;
    private Instant createdAt = Instant.now();
    public StoredObject() {}
    public String getId(){return id;} public void setId(String id){this.id=id;}
    public String getTenantId(){return tenantId;} public void setTenantId(String tenantId){this.tenantId=tenantId;}
    public String getCallId(){return callId;} public void setCallId(String callId){this.callId=callId;}
    public String getObjectKey(){return objectKey;} public void setObjectKey(String objectKey){this.objectKey=objectKey;}
    public String getUrl(){return url;} public void setUrl(String url){this.url=url;}
    public String getBackend(){return backend;} public void setBackend(String backend){this.backend=backend;}
    public Instant getCreatedAt(){return createdAt;} public void setCreatedAt(Instant createdAt){this.createdAt=createdAt;}
}
