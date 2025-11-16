package com.dalai.llama.blobmanager.service;

import com.dalai.llama.blobmanager.model.StoredObject;
import com.dalai.llama.blobmanager.repository.StoredObjectRepository;
import com.dalai.llama.blobmanager.storage.StorageFacade;
import com.dalai.llama.blobmanager.kafka.KafkaPublisher;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class BlobService {

    private final StorageFacade storageFacade;
    private final StoredObjectRepository storedRepo;
    private final KafkaPublisher publisher;
    private final ObjectMapper mapper = new ObjectMapper();

    /**
     * Ingest a recording: upload to cloud/local backend,
     * persist metadata in DB, and publish Kafka event.
     */
    @Transactional
    public StoredObject ingest(String configId, String tenantId, String callId, String localPath) throws Exception {

        log.info("➡️ Ingest request: cfg={}, tenant={}, call={}, path={}",
                configId, tenantId, callId, localPath);

        // call storage provider through facade
        String resultJson = storageFacade.uploadUsingConfig(configId, tenantId, localPath);

        JsonNode parsed = mapper.readTree(resultJson);
        String url = parsed.get("url").asText();
        String objectId = parsed.get("objectId").asText();

        StoredObject obj = new StoredObject();
        obj.setTenantId(tenantId);
        obj.setCallId(callId);
        obj.setObjectKey(objectId);
        obj.setUrl(url);
        obj.setBackend(configId);

        storedRepo.save(obj);

        // Publish recording event to Kafka
        publisher.publish(tenantId, callId, url, objectId);

        log.info("✅ Stored recording object {} for tenant {} call {} at {}", objectId, tenantId, callId, url);

        return obj;
    }

    /**
     * List objects for the tenant or call.
     */
    public List<StoredObject> list(String tenantId, String callId) {
        if (callId == null || callId.isBlank()) {
            return storedRepo.findByTenantId(tenantId);
        }
        return storedRepo.findByTenantIdAndCallId(tenantId, callId);
    }

    /**
     * Delete an object from cloud provider and local DB.
     */
    @Transactional
    public boolean delete(String tenantId, String objectId) throws Exception {
        StoredObject obj = storedRepo.findById(objectId).orElse(null);
        if (obj == null) {
            log.warn("⚠️ Cannot delete: object {} not found", objectId);
            return false;
        }

        storageFacade.deleteUsingConfig(obj.getBackend(), tenantId, objectId);
        storedRepo.delete(obj);
        log.info("🗑️ Deleted object {} for tenant {}", objectId, tenantId);

        return true;
    }
}
