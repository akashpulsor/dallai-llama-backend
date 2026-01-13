package com.dalai.llama.pbx.core.controller;

import com.dalai.llama.pbx.core.model.IvrNode;
import com.dalai.llama.pbx.core.service.IvrService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/tenants/{tenantId}/ivr")
@RequiredArgsConstructor
public class IvrController {

    private final IvrService ivrService;

    @GetMapping
    public List<IvrNode> list(@PathVariable String tenantId) {
        return ivrService.list(tenantId);
    }

    @PostMapping
    public IvrNode create(
            @PathVariable String tenantId,
            @RequestBody IvrNode req
    ) {
        return ivrService.create(tenantId, req);
    }

    @PutMapping("/{nodeId}")
    public IvrNode update(
            @PathVariable String tenantId,
            @PathVariable String nodeId,
            @RequestBody IvrNode req
    ) {
        return ivrService.update(tenantId, nodeId, req);
    }

    @DeleteMapping("/{nodeId}")
    public void delete(
            @PathVariable String tenantId,
            @PathVariable String nodeId
    ) {
        ivrService.delete(tenantId, nodeId);
    }

    @PostMapping("/reload")
    public String reload(@PathVariable String tenantId) {
        ivrService.rebuildAndReload(tenantId);
        return "OK";
    }
}
