package com.dalai.llama.blobmanager.storage;

public interface StorageDriver {

    String upload(String tenantId, String localPath) throws Exception;

    boolean delete(String tenantId, String objectId) throws Exception;
}
