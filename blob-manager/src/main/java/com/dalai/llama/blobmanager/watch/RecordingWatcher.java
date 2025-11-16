
package com.dalai.llama.blobmanager.watch;



import com.dalai.llama.blobmanager.service.BlobService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.File;
import java.util.HashSet;
import java.util.Set;

@Slf4j
@Component
public class RecordingWatcher {

    private final BlobManagerProperties props;
    private final BlobService blobService;
    private final Set<String> seen = new HashSet<>();

    public RecordingWatcher(BlobManagerProperties props, BlobService blobService) {
        this.props = props;
        this.blobService = blobService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onReady() {
        if (!props.isWatcherEnabled()) {
            log.info("RecordingWatcher disabled by configuration.");
            return;
        }
        log.info("RecordingWatcher started. watching {}", props.getWatchDir());
    }

    @Scheduled(fixedDelayString = "#{${blob.poll-interval-sec:5} * 1000}")
    public void poll() {
        try {
            if (!props.isWatcherEnabled()) return;
            File dir = new File(props.getWatchDir());
            if (!dir.exists() || !dir.isDirectory()) {
                log.warn("watch dir missing: {}", props.getWatchDir());
                return;
            }
            File[] files = dir.listFiles((d, name) -> name.endsWith(".wav") || name.endsWith(".mp3"));
            if (files == null) return;
            for (File f : files) {
                String path = f.getAbsolutePath();
                if (seen.contains(path)) continue;
                log.info("Found new recording: {}", path);

                String filename = f.getName();
                String callId = filename.replaceFirst("[.][^.]+$", "");
                String tenantId = System.getenv().getOrDefault("TENANT_ID","unknown");
                String configId = System.getenv().getOrDefault("CLOUD_CONFIG_ID","default");

                try {
                    blobService.ingest(configId, tenantId, callId, path);
                    seen.add(path);
                    log.info("Uploaded and published for call {} -> {}", callId, path);
                } catch (Exception e) {
                    log.error("Upload failed for {}: {}", path, e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            log.error("Watcher poll error: {}", e.getMessage(), e);
        }
    }
}
