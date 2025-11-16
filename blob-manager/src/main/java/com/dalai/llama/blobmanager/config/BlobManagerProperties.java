package com.dalai.llama.blobmanager.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "blob")
public class BlobManagerProperties {
    private String watchDir = "/var/spool/rtpengine/recordings";
    private int pollIntervalSec = 5;
    private String backend = "s3";
    private boolean watcherEnabled = true;

    public String getWatchDir() { return watchDir; }
    public void setWatchDir(String watchDir) { this.watchDir = watchDir; }
    public int getPollIntervalSec() { return pollIntervalSec; }
    public void setPollIntervalSec(int pollIntervalSec) { this.pollIntervalSec = pollIntervalSec; }
    public String getBackend() { return backend; }
    public void setBackend(String backend) { this.backend = backend; }
    public boolean isWatcherEnabled() { return watcherEnabled; }
    public void setWatcherEnabled(boolean watcherEnabled) { this.watcherEnabled = watcherEnabled; }
}
