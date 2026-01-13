package com.dalai.llama.pbx.core.provision;


import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConfigUpdaterService {

    private final KubernetesClient k8s;

    // -----------------------------
    // Update CM and reload servers
    // -----------------------------
    public void updateConfigsAndReload(
            String tenantId,
            String fsConfigMapName,
            Map<String,String> fsFiles,
            String kamConfigMapName,
            Map<String,String> kamFiles,
            String dispatcherFilename
    ) {
        String namespace = "tenant-" + tenantId;

        updateConfigMap(namespace, fsConfigMapName, fsFiles);
        updateConfigMap(namespace, kamConfigMapName, kamFiles);

        reloadFreeSwitch(namespace);
        reloadKamailio(namespace);
    }

    // -----------------------------
    // Update CM
    // -----------------------------
    private void updateConfigMap(String ns, String name, Map<String,String> files) {
        log.info("Updating ConfigMap {} in namespace {}", name, ns);

        var cm = new ConfigMapBuilder()
                .withNewMetadata().withName(name).endMetadata()
                .withData(files)
                .build();

        k8s.configMaps()
                .inNamespace(ns)
                .createOrReplace(cm);
    }

    // -----------------------------
    // FreeSWITCH reload
    // -----------------------------
    private void reloadFreeSwitch(String ns) {
        String pod = findPod(ns, "freeswitch");

        log.info("Reloading FreeSWITCH in pod {}", pod);
        exec(ns, pod, new String[]{
                "sh", "-c", "fs_cli -x 'reloadxml'"
        });
    }

    // -----------------------------
    // Kamailio reload
    // -----------------------------
    private void reloadKamailio(String ns) {
        String pod = findPod(ns, "kamailio");

        log.info("Reloading Kamailio dispatcher + cfg in pod {}", pod);
        exec(ns, pod, new String[]{
                "sh", "-c", "kamcmd dispatcher.reload"
        });
        exec(ns, pod, new String[]{
                "sh", "-c", "kamcmd cfg.reload"
        });
    }

    // -----------------------------
    // Exec helper
    // -----------------------------
    private void exec(String ns, String pod, String[] cmd) {
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ByteArrayOutputStream err = new ByteArrayOutputStream();

            k8s.pods()
                    .inNamespace(ns)
                    .withName(pod)
                    .writingOutput(out)
                    .writingError(err)
                    .exec(cmd);

            Thread.sleep(1500);

            log.info("OUT: {}", out.toString());
            log.info("ERR: {}", err.toString());

        } catch (Exception ex) {
            log.error("Exec failed: {}", ex.getMessage());
        }
    }

    private String findPod(String ns, String appPrefix) {
        return k8s.pods()
                .inNamespace(ns)
                .list()
                .getItems()
                .stream()
                .filter(p -> p.getMetadata().getName().startsWith(appPrefix))
                .findFirst()
                .orElseThrow(() -> new RuntimeException("Pod not found: " + appPrefix))
                .getMetadata()
                .getName();
    }
}
