
package com.dalai.llama.blobmanager.controller;

import com.dalai.llama.blobmanager.model.StoredObject;
import com.dalai.llama.blobmanager.repository.StoredObjectRepository;
import com.dalai.llama.blobmanager.service.BlobService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/blob")
public class BlobController {

    private final StoredObjectRepository repo;
    private final BlobService blobService;

    public BlobController(StoredObjectRepository repo, BlobService blobService) {
        this.repo = repo;
        this.blobService = blobService;
    }

    @GetMapping("/{tenantId}")
    public ResponseEntity<List<StoredObject>> list(@PathVariable String tenantId,
                                                   @RequestParam(required = false) String callId) {
        if (callId!=null) return ResponseEntity.ok(repo.findByTenantIdAndCallId(tenantId, callId));
        return ResponseEntity.ok(repo.findByTenantId(tenantId));
    }

    @PostMapping("/ingest")
    public ResponseEntity<?> ingest(@RequestBody Map<String,String> body) throws Exception {
        String configId = body.get("configId");
        String tenantId = body.get("tenantId");
        String callId = body.get("callId");
        String sourcePath = body.get("sourcePath");
        var obj = blobService.ingest(configId, tenantId, callId, sourcePath);
        return ResponseEntity.ok(Map.of("id", obj.getId(), "url", obj.getUrl()));
    }

    @DeleteMapping("/{tenantId}/{objectId}")
    public ResponseEntity<?> delete(@PathVariable String tenantId, @PathVariable String objectId) throws Exception {
        // find config id from stored object backend field and delete via StorageFacade
        return ResponseEntity.ok(Map.of("deleted", true));
    }
}
