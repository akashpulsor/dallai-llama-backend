package com.dalai.llama.creativeplanning.dto;

import java.util.UUID;

/** What locking an option actually produces: the LockedIdea record itself, plus the id of the
 * pre-production-service Project it was synchronously used to create -- callers need this to
 * navigate the creator to the project that now exists, not just know locking "succeeded". */
public record LockIdeaOptionResponse(
        LockedIdeaView lockedIdea,
        UUID projectId
) {
}
