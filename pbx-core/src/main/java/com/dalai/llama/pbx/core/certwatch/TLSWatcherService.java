package com.dalai.llama.pbx.core.certwatch;

import com.dalai.llama.pbx.core.model.SignalingConfig;
import com.dalai.llama.pbx.core.repository.SignalingConfigRepository;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class TLSWatcherService {

    private final KubernetesClient k8s;
    private final SignalingConfigRepository repo;

    // Every 12 hours
    @Scheduled(fixedRate = 43200000)
    public void checkTenantCerts() {
        List<SignalingConfig> all = repo.findAll();
        for (SignalingConfig cfg : all) {
            try {
                String ns = "pbx-shared";
                if (cfg.getTenantId() != null && cfg.getTenantId().length() > 5) {
                    ns = "tenant-" + cfg.getTenantId();
                }
                String[] secrets = {cfg.getSipTlsSecret(), cfg.getTurnTlsSecret(), cfg.getWssTlsSecret()};
                for (String secName : secrets) {
                    if (secName == null || secName.isBlank()) continue;
                    Secret s = k8s.secrets().inNamespace(ns).withName(secName).get();
                    if (s == null || s.getData() == null) continue;
                    String crtB64 = s.getData().get("tls.crt");
                    if (crtB64 == null) continue;

                    byte[] crtBytes = Base64.getDecoder().decode(crtB64);
                    CertificateFactory cf = CertificateFactory.getInstance("X.509");
                    X509Certificate cert = (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(crtBytes));

                    cfg.setCertIssuedAt(cert.getNotBefore().toInstant());
                    cfg.setCertExpiresAt(cert.getNotAfter().toInstant());
                    repo.save(cfg);

                    log.info("Updated TLS expiry for tenant {} secret {} → {}", cfg.getTenantId(), secName, cert.getNotAfter());
                }
            } catch (Exception e) {
                log.warn("TLSWatcher error for tenant {}: {}", cfg.getTenantId(), e.getMessage());
            }
        }
    }
}
