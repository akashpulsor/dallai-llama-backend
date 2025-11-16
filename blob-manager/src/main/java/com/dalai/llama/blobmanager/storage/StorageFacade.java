
package com.dalai.llama.blobmanager.storage;

import com.dalai.llama.blobmanager.model.CloudConfig;
import com.dalai.llama.blobmanager.repository.CloudConfigRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.util.Map;

@Component
public class StorageFacade {

    private final CloudConfigRepository configRepo;
    private final ObjectMapper mapper = new ObjectMapper();

    public StorageFacade(CloudConfigRepository configRepo) {
        this.configRepo = configRepo;
    }

    private Map<String,String> loadProps(CloudConfig cfg) throws Exception {
        if (cfg.getProps()==null) return Map.of();
        return mapper.readValue(cfg.getProps(), Map.class);
    }

    public String uploadUsingConfig(String configId, String tenantId, String localPath) throws Exception {
        CloudConfig cfg = configRepo.findById(configId).orElseThrow();
        String prov = cfg.getProvider();
        Map<String,String> props = loadProps(cfg);
        if ("local".equalsIgnoreCase(prov)) {
            LocalStorageDriver driver = new LocalStorageDriver(props);
            return driver.upload(tenantId, localPath);
        } else {
            S3StorageDriver driver = new S3StorageDriver(props);
            return driver.upload(tenantId, localPath);
        }
    }

    public boolean deleteUsingConfig(String configId, String tenantId, String objectId) throws Exception {
        CloudConfig cfg = configRepo.findById(configId).orElseThrow();
        String prov = cfg.getProvider();
        Map<String,String> props = loadProps(cfg);
        if ("local".equalsIgnoreCase(prov)) {
            LocalStorageDriver driver = new LocalStorageDriver(props);
            return driver.delete(tenantId, objectId);
        } else {
            S3StorageDriver driver = new S3StorageDriver(props);
            return driver.delete(tenantId, objectId);
        }
    }

}
