package com.dalai.llama.llmgateway.service.provider;

import reactor.core.publisher.Mono;

/**
 * One adapter per provider. Implementations own request translation and provider-specific
 * error mapping (see {@link LlmProviderException}) so callers can treat every provider
 * uniformly.
 *
 * <p>Returns a cold {@link Mono} rather than blocking so the caller ({@code LlmGatewayService})
 * can subscribe non-blockingly, register the subscription against the job's id, and actually
 * cancel the underlying HTTP call if the user cancels the job (see
 * {@code InFlightJobRegistry}) -- a blocking {@code generate()} would give the caller nothing
 * to cancel.
 */
public interface LlmProvider {

    Mono<LlmResponse> generate(CanonicalRequest request);

    /** The {@code provider_id} row this adapter implements, e.g. {@code "google"}. */
    String providerId();

    /** Best-effort request to actually stop generation on the provider's own infrastructure, not
     * just stop watching it locally -- for a queue-based provider (fal.ai's submit-then-poll),
     * disposing our own polling subscription alone leaves the provider still generating (and
     * still billing) a result nobody will ever retrieve. {@code providerRequestId} is whatever
     * the provider's adapter needs to identify the specific in-flight call (fal.ai: its own
     * {@code request_id} from the submit response) -- generic across providers so any future
     * queue-based adapter implements the same contract without inventing its own cancel path.
     * A provider with no server-side job concept (a single synchronous call, e.g.
     * {@code GoogleGeminiProvider}) has nothing to cancel once dispatched, hence the no-op
     * default. */
    default void cancelRequest(String providerRequestId) {
    }
}
