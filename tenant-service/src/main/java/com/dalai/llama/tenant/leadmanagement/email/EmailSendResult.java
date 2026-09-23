package com.dalai.llama.tenant.leadmanagement.email;

import lombok.Builder;

/** Result of one send attempt. Neither the transport nor the provider is specified here on
 * purpose -- Phase 1 only defines the shape. {@code providerMessageId} is nullable because
 * the eventual provider may deliver asynchronously and have no id at the moment of the call. */
@Builder
public record EmailSendResult(
        boolean accepted,
        String providerMessageId,
        String error
) {
    public static EmailSendResult accepted(String providerMessageId) {
        return EmailSendResult.builder().accepted(true).providerMessageId(providerMessageId).build();
    }

    public static EmailSendResult rejected(String error) {
        return EmailSendResult.builder().accepted(false).error(error).build();
    }
}
