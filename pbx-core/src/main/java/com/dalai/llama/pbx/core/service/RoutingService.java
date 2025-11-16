package com.dalai.llama.pbx.core.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.dalai.llama.pbx.core.dto.IngressCallRequest;
import com.dalai.llama.pbx.core.dto.OutboundCallRequest;
import com.dalai.llama.pbx.core.dto.OutboundRouteResponse;
import com.dalai.llama.pbx.core.dto.RouteDecisionResponse;
import com.dalai.llama.pbx.core.kafka.RouteEventProducer;
import com.dalai.llama.pbx.core.model.Did; // <-- **NEW IMPORT**
import com.dalai.llama.pbx.core.model.RoutingPolicy;
import com.dalai.llama.pbx.core.repository.DidRepository; // <-- **NEW IMPORT**
import com.dalai.llama.pbx.core.repository.RoutingPolicyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpMethod;
import org.springframework.http.RequestEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.regex.Pattern; // <-- Import Pattern

@Slf4j
@Service
@RequiredArgsConstructor
public class RoutingService {

    private final RoutingPolicyRepository policyRepo;
    private final DidRepository didRepository; // <-- **NEW: Inject DidRepository**
    private final StringRedisTemplate redis;
    private final RouteEventProducer producer;
    private final EgressService egressService;
    private final ObjectMapper om = new ObjectMapper();

    @Value("${pbx.ai.routing.timeout-ms:60}")
    private int defaultAiTimeoutMs;

    // Simple regex to detect E.164 PSTN format (e.g., +15551234567)
    private static final Pattern PSTN_PATTERN = Pattern.compile("^\\+[1-9]\\d{1,14}$");

    public RouteDecisionResponse route(IngressCallRequest req) {

        String destination = req.getEntrypoint(); // This is the $rU (Request-URI User) from Kamailio
        String tenantId = req.getTenantId();

        // **NEW LOGIC FLOW: Check call type**

        // 1. Is this an INBOUND call from a DID registered to this tenant?
        // FIX: Using findByTenantIdAndNumber as per the latest repository definition.
        Optional<Did> inboundDid = didRepository.findByTenantIdAndNumber(tenantId, destination);

        if (inboundDid.isPresent()) {
            // This is an INBOUND call from the PSTN where the dialed number (destination) is a DID we own.
            // We must find the RoutingPolicy associated with this DID's entrypoint.
            String didEntrypoint = inboundDid.get().getEntrypoint();
            log.info("Detected INBOUND call for tenant {}, DID {} -> entrypoint {}", tenantId, destination, didEntrypoint);
            return handleInboundToAgent(req, didEntrypoint);
        }

        // 2. Is this an OUTBOUND call to the PSTN?
        if (isPstnNumber(destination)) {
            // The destination is a PSTN number, but it's NOT one of our DIDs.
            // This must be an OUTBOUND call from an agent.
            log.info("Detected OUTBOUND PSTN call for tenant {}, from {} to {}", tenantId, req.getFrom(), destination);
            return handlePstnEgress(req);
        }

        // 3. If not INBOUND or OUTBOUND, it must be INTERNAL (e.g., agent-to-agent, agent-to-queue)
        // We use the 'destination' as the entrypoint key directly.
        log.info("Detected INTERNAL call for tenant {}, entrypoint {}", tenantId, destination);
        return handleInboundToAgent(req, destination);
    }

    /**
     * **NEW: Refactored logic for routing to an agent/policy**
     */
    private RouteDecisionResponse handleInboundToAgent(IngressCallRequest req, String entrypoint) {
        RoutingPolicy policy = policyRepo.findByTenantIdAndEntrypoint(req.getTenantId(), entrypoint)
                .orElseThrow(() -> new RuntimeException("No routing policy for entrypoint: " + entrypoint));

        List<String> eligibleAgents = getEligibleAgents(req.getTenantId(), policy);
        List<RouteDecisionResponse.Target> targets = new ArrayList<>();

        String strategy = policy.getStrategy();
        if ("ai_hook".equalsIgnoreCase(strategy) && policy.getAiEndpoint() != null) {
            String agentId = invokeAiHook(req, policy);
            if (agentId != null) {
                String contact = resolveBestContact(agentId);
                if (contact != null) {
                    targets.add(RouteDecisionResponse.Target.builder()
                            .type("agent").agentId(agentId).contact(contact).build());
                }
            }
        } else {
            // deterministic fallback strategies
            String selected = switch (strategy) {
                case "round_robin" -> nextRoundRobin(req.getTenantId(), policy.getTeamId(), eligibleAgents);
                case "longest_idle" -> popLongestIdle(req.getTenantId(), policy.getTeamId(), eligibleAgents);
                case "priority-weighted" -> pickWeighted(eligibleAgents);
                default -> eligibleAgents.isEmpty() ? null : eligibleAgents.getFirst();
            };
            if (selected != null) {
                String contact = resolveBestContact(selected);
                if (contact != null) {
                    targets.add(RouteDecisionResponse.Target.builder()
                            .type("agent").agentId(selected).contact(contact).build());
                }
            }
        }

        // Failover if no targets
        if (targets.isEmpty()) {
            log.warn("No agent targets found for entrypoint {}. Failing over.", entrypoint);
            return RouteDecisionResponse.builder()
                    .decision("fallback").strategyApplied(strategy).ttlMs(30000).targets(List.of()).build();
        }

        RouteDecisionResponse response = RouteDecisionResponse.builder()
                .decision("deliver").strategyApplied(strategy).ttlMs(30000).targets(targets).build();

        // Publish to Kafka for Agent GW / Signaling observability
        try {
            Map<String,Object> evt = new HashMap<>();
            evt.put("type","call.route.decided");
            evt.put("tenant_id", req.getTenantId());
            evt.put("call_id", req.getCallId());
            evt.put("targets", targets);
            producer.publish("call.route.decided", req.getTenantId()+":"+req.getCallId(), om.writeValueAsString(evt));
        } catch (Exception ignore){}

        return response;
    }


    /**
     * Handles routing for outbound PSTN calls
     */
    private RouteDecisionResponse handlePstnEgress(IngressCallRequest req) {
        // 1. Create a request for the EgressService
        OutboundCallRequest egressReq = OutboundCallRequest.builder()
                .tenantId(req.getTenantId())
                .from(req.getFrom())
                .to(req.getEntrypoint()) // The dialed PSTN number
                .callId(req.getCallId())
                .build();

        // 2. Call EgressService to find a trunk (this is defined in your OutboundController)
        OutboundRouteResponse egressResp;
        try {
            egressResp = egressService.selectTrunk(egressReq);
        } catch (Exception e) {
            log.error("EgressService failed to select trunk for tenant {}: {}", req.getTenantId(), e.getMessage());
            egressResp = null;
        }

        // 3. If no trunk, reject the call
        // Using .getTrunkUri() to match OutboundRouteResponse DTO
        if (egressResp == null || egressResp.getTrunkUri() == null || egressResp.getTrunkUri().isBlank()) {
            log.warn("No suitable egress trunk found for tenant {} to {}", req.getTenantId(), req.getEntrypoint());
            return RouteDecisionResponse.builder()
                    .decision("fallback").strategyApplied("egress_pstn_fail").ttlMs(30000).targets(List.of()).build();
        }

        // 4. Build the target for Kamailio.
        // The EgressService must return the *full* SIP URI Kamailio needs.
        // Your EgressService current returns 'trunkUri' which is the base (e.g., trunk.provider.com)
        // We must construct the full SIP URI.
        String outboundSipUri = String.format("sip:%s@%s", req.getEntrypoint(), egressResp.getTrunkUri());

        log.info("Routing outbound call to trunk: {}", outboundSipUri);

        List<RouteDecisionResponse.Target> targets = List.of(
                RouteDecisionResponse.Target.builder()
                        .type("trunk")
                        .contact(outboundSipUri) // e.g., "sip:+15551234567@trunk.provider.com"
                        .build()
        );

        // 5. Send the routing decision back to Kamailio
        return RouteDecisionResponse.builder()
                .decision("deliver").strategyApplied("egress_pstn").ttlMs(30000).targets(targets).build();
    }

    /**
     * Helper to check if a number is PSTN
     */
    private boolean isPstnNumber(String number) {
        if (number == null) {
            return false;
        }
        return PSTN_PATTERN.matcher(number).matches();
    }

    private List<String> getEligibleAgents(String tenantId, RoutingPolicy policy) {
        // Expect ZSET presence: team:eligible:{tenant}:{team}
        String key = "team:eligible:%s:%s".formatted(tenantId, policy.getTeamId());
        // For simplicity, read as LIST of agent IDs
        List<String> agents = redis.opsForList().range(key, 0, -1);
        return agents == null ? List.of() : agents;
    }

    private String nextRoundRobin(String tenantId, String teamId, List<String> agents) {
        if (agents.isEmpty()) return null;
        String pointerKey = "rr:pointer:%s:%s".formatted(tenantId, teamId);
        Long idx = redis.opsForValue().increment(pointerKey);
        int i = (int) ((idx == null ? 0 : idx) % agents.size());
        return agents.get(i);
        // Note: in production use atomic scripts for wrap-around
    }

    private String popLongestIdle(String tenantId, String teamId, List<String> agents) {
        // Expect ZSET: idle:team:{tenant}:{team} with score=idleSeconds
        String zkey = "idle:team:%s:%s".formatted(tenantId, teamId);
        Set<String> top = redis.opsForZSet().reverseRange(zkey, 0, 0);
        if (top != null && !top.isEmpty()) return top.iterator().next();
        return agents.isEmpty() ? null : agents.get(0);
    }

    private String pickWeighted(List<String> agents) {
        if (agents.isEmpty()) return null;
        int i = ThreadLocalRandom.current().nextInt(agents.size());
        return agents.get(i);
    }

    private String resolveBestContact(String agentId) {
        // contacts:agent:{agent} -> list like ["webrtc:wss://…", "sip:101@x"]
        String key = "contacts:agent:%s".formatted(agentId);
        List<String> contacts = redis.opsForList().range(key, 0, -1);
        return (contacts == null || contacts.isEmpty()) ? null : contacts.get(0);
    }

    private String invokeAiHook(IngressCallRequest req, RoutingPolicy policy) {
        try {
            int timeout = Optional.ofNullable(policy.getAiTimeoutMs()).orElse(defaultAiTimeoutMs);
            RestClient client = RestClient.builder().build();
            URI uri = URI.create(policy.getAiEndpoint());
            var body = Map.of(
                    "tenant", req.getTenantId(),
                    "callId", req.getCallId(),
                    "from", req.getFrom(),
                    "to", req.getTo(),
                    "entrypoint", req.getEntrypoint()
            );
            var request = RequestEntity.method(HttpMethod.POST, uri).body(body);
            var resp = client
                    .post()
                    .uri(uri)
                    .body(body)
                    .retrieve()
                    .toEntity(Map.class);
            if (resp.getStatusCode().is2xxSuccessful() && resp.getBody()!=null) {
                Object routeTo = resp.getBody().get("route_to");
                return routeTo == null ? null : routeTo.toString();
            }
        } catch (Exception ignored) {}
        return null;
    }
}