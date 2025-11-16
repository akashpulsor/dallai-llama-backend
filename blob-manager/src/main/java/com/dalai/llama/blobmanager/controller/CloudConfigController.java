
package com.dalai.llama.blobmanager.controller;

import com.dalai.llama.blobmanager.model.CloudConfig;
import com.dalai.llama.blobmanager.repository.CloudConfigRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
//import org.springframework.http.ResponseEntity;
//import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/cloud")
public class CloudConfigController {

    private final CloudConfigRepository repo;

    public CloudConfigController(CloudConfigRepository repo) {
        this.repo = repo;
    }

    @PostMapping
    public ResponseEntity<CloudConfig> create(@RequestBody CloudConfig cfg) {
        return ResponseEntity.ok(repo.save(cfg));
    }

    @GetMapping
    public ResponseEntity<List<CloudConfig>> list() {
        return ResponseEntity.ok(repo.findAll());
    }

    @GetMapping("/{id}")
    public ResponseEntity<CloudConfig> get(@PathVariable String id) {
        return ResponseEntity.of(repo.findById(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable String id) {
        repo.deleteById(id);
        return ResponseEntity.ok(Map.of("deleted", true));
    }
}
