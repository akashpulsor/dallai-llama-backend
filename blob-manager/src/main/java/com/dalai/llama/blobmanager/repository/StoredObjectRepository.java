
package com.dalai.llama.blobmanager.repository;

import com.dalai.llama.blobmanager.model.StoredObject;
import org.springframework.data.jpa.repository.JpaRepository;
//import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoredObjectRepository extends JpaRepository<StoredObject, String> {
    List<StoredObject> findByTenantId(String tenantId);
    List<StoredObject> findByTenantIdAndCallId(String tenantId, String callId);
}
