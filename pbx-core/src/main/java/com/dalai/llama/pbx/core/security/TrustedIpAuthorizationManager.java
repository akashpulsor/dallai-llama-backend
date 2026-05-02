package com.dalai.llama.pbx.core.security;

import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authorization.AuthorizationDecision;
import org.springframework.security.authorization.AuthorizationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.access.intercept.RequestAuthorizationContext;
import org.springframework.security.web.util.matcher.IpAddressMatcher;

import java.util.List;
import java.util.function.Supplier;

/**
 * Restricts access to requests originating from trusted IP addresses / CIDR ranges.
 *
 * Used for telecom-infra endpoints (FreeSWITCH, Kamailio, RTPEngine)
 * that run on bare-metal hosts outside the Kubernetes cluster.
 *
 * Checks both {@code remoteAddr} and {@code X-Forwarded-For} header
 * (first hop) to handle reverse-proxy / Istio sidecar scenarios.
 */
@Slf4j
public class TrustedIpAuthorizationManager
        implements AuthorizationManager<RequestAuthorizationContext> {

    private final List<IpAddressMatcher> matchers;

    public TrustedIpAuthorizationManager(List<String> trustedNetworks) {
        this.matchers = trustedNetworks.stream()
                .map(IpAddressMatcher::new)
                .toList();
        log.info("Trusted IP networks configured: {}", trustedNetworks);
    }

    @Override
    public AuthorizationDecision check(
            Supplier<Authentication> authentication,
            RequestAuthorizationContext context) {

        String remoteAddr = context.getRequest().getRemoteAddr();

        // Also check X-Forwarded-For (first hop) for reverse-proxy scenarios
        String forwarded = context.getRequest().getHeader("X-Forwarded-For");
        String clientIp = (forwarded != null && !forwarded.isBlank())
                ? forwarded.split(",")[0].trim()
                : remoteAddr;

        boolean trusted = matchers.stream()
                .anyMatch(m -> m.matches(remoteAddr) || m.matches(clientIp));

        if (!trusted) {
            log.warn("Rejected request from untrusted IP: remoteAddr={}, clientIp={}, path={}",
                    remoteAddr, clientIp, context.getRequest().getRequestURI());
        }

        return new AuthorizationDecision(trusted);
    }
}
