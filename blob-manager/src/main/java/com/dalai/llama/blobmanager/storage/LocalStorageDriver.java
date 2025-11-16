package com.dalai.llama.blobmanager.storage;

import java.nio.file.*;
import java.util.Map;
import java.util.UUID;

public class LocalStorageDriver implements StorageDriver {

    private final Path base;

    public LocalStorageDriver(Map<String,String> props) {
        String root = props.getOrDefault("root","/data/recordings");
        this.base = Paths.get(root);
    }

    @Override
    public String upload(String tenantId, String localPath) throws Exception {
        Path src = Paths.get(localPath);
        String objectId = UUID.randomUUID().toString();
        Path destDir = base.resolve(tenantId).resolve(objectId.substring(0,2));
        Files.createDirectories(destDir);
        Path dest = destDir.resolve(objectId + "-" + src.getFileName().toString());
        Files.copy(src, dest, StandardCopyOption.REPLACE_EXISTING);
        String url = dest.toAbsolutePath().toString();

        return String.format("{\"url\":\"%s\",\"objectId\":\"%s\"}", url, objectId);
    }

    @Override
    public boolean delete(String tenantId, String objectId) throws Exception {
        Path dir = base.resolve(tenantId);
        if (!Files.exists(dir)) return false;

        try (var s = Files.walk(dir)) {
            var found = s.filter(p -> p.getFileName().toString().startsWith(objectId + "-")).findFirst();
            if (found.isPresent()) {
                Files.deleteIfExists(found.get());
                return true;
            }
        }
        return false;
    }
}
