package com.dalai.llama.pbx.core.service;

import com.dalai.llama.pbx.core.model.IvrNode;
import com.dalai.llama.pbx.core.model.IvrDtmfMap;
import com.dalai.llama.pbx.core.repository.IvrNodeRepository;
import com.dalai.llama.pbx.core.repository.IvrDtmfRepository;
import com.dalai.llama.pbx.core.util.DynamicFreeSwitchDialplanBuilder;
import com.dalai.llama.pbx.core.util.DynamicKamailioConfigBuilder;
import com.dalai.llama.pbx.core.provision.ConfigUpdaterService;
import com.dalai.llama.pbx.core.util.DynamicFreeSwitchDirectoryBuilder;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class IvrService {

    private final IvrNodeRepository nodeRepo;
    private final IvrDtmfRepository dtmfRepo;

    private final DynamicFreeSwitchDialplanBuilder fsDialplanBuilder;
    private final DynamicFreeSwitchDirectoryBuilder fsDirectoryBuilder;
    private final DynamicKamailioConfigBuilder kamBuilder;

    private final ConfigUpdaterService configUpdater;

    // ---------------------------
    // CRUD OPERATIONS
    // ---------------------------

    public List<IvrNode> list(String tenantId) {
        return nodeRepo.findByTenantId(tenantId);
    }

    @Transactional
    public IvrNode create(String tenantId, IvrNode req) {
        req.setTenantId(tenantId);
        IvrNode saved = nodeRepo.save(req);

        saveDtmf(saved, req.getDtmfMappings());
        rebuildAndReload(tenantId);

        return saved;
    }

    @Transactional
    public IvrNode update(String tenantId, String nodeId, IvrNode req) {
        IvrNode existing = nodeRepo.findByTenantIdAndNodeId(tenantId, nodeId)
                .orElseThrow(() -> new RuntimeException("IVR Node not found"));

        existing.setPrompt(req.getPrompt());
        existing.setBotEnabled(req.isBotEnabled());
        existing.setParentNodeId(req.getParentNodeId());
        existing.setTransferNumber(req.getTransferNumber());
        existing.setFinalNode(req.isFinalNode());

        dtmfRepo.deleteByNode_Id(existing.getId());
        saveDtmf(existing, req.getDtmfMappings());

        IvrNode updated = nodeRepo.save(existing);

        rebuildAndReload(tenantId);
        return updated;
    }

    @Transactional
    public void delete(String tenantId, String nodeId) {
        nodeRepo.findByTenantIdAndNodeId(tenantId, nodeId)
                .ifPresent(node -> {
                    dtmfRepo.deleteByNode_Id(node.getId());
                    nodeRepo.delete(node);
                });

        rebuildAndReload(tenantId);
    }

    // ---------------------------
    // HELPERS
    // ---------------------------

    private void saveDtmf(IvrNode parent, List<IvrDtmfMap> mappings) {
        if (mappings == null) return;
        mappings.forEach(m -> {
            m.setNode(parent);
            dtmfRepo.save(m);
        });
    }

    // ---------------------------
    // REBUILD & RELOAD
    // ---------------------------

    public void rebuildAndReload(String tenantId) {
        List<IvrNode> nodes = nodeRepo.findByTenantId(tenantId);

        // Build FS dialplan & directory
        String dialplanXml = fsDialplanBuilder.buildDialplan(tenantId, nodes);
        String directoryXml = fsDirectoryBuilder.buildDirectoryXml(tenantId);

        // Build Kamailio IVR additions (optional)
        String kamailioCfg = kamBuilder.buildForIVR(tenantId, nodes);

        // Prepare config maps
        Map<String, String> fsFiles = Map.of(
                "dialplan.xml", dialplanXml,
                "directory.xml", directoryXml
        );

        Map<String, String> kamFiles = Map.of(
                "kamailio.cfg", kamailioCfg
        );

        // Update config + reload FS & Kamailio
        configUpdater.updateConfigsAndReload(
                tenantId,
                "freeswitch-config-" + tenantId,
                fsFiles,
                "kamailio-config-" + tenantId,
                kamFiles,
                "dispatcher.list"   // unchanged, but required dependency
        );
    }
}
