package com.dalai.llama.tenant.showcase.service;

import com.dalai.llama.tenant.showcase.domain.ProfileStatus;
import com.dalai.llama.tenant.showcase.domain.entity.CreatorPublicProfile;
import com.dalai.llama.tenant.showcase.repository.CreatorPublicProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** A signed-in brand following a creator. Idempotent both ways; the profile's follower count moves
 * only when a row is actually added or removed. */
@Service
@RequiredArgsConstructor
public class FollowService {

    private final CreatorPublicProfileRepository profileRepository;
    private final JdbcTemplate jdbc;

    public record FollowState(boolean following, int followerCount) {
    }

    @Transactional
    public FollowState follow(UUID brandContactId, String handle) {
        CreatorPublicProfile profile = activeProfile(handle);
        int added = jdbc.update("""
                INSERT INTO creator_follower (tenant_id, brand_contact_id) VALUES (?, ?)
                ON CONFLICT (tenant_id, brand_contact_id) DO NOTHING""", profile.getTenantId(), brandContactId);
        if (added == 1) jdbc.update("UPDATE creator_public_profile SET follower_count = follower_count + 1 WHERE tenant_id = ?",
                profile.getTenantId());
        return state(profile.getTenantId(), true);
    }

    @Transactional
    public FollowState unfollow(UUID brandContactId, String handle) {
        CreatorPublicProfile profile = activeProfile(handle);
        int removed = jdbc.update("DELETE FROM creator_follower WHERE tenant_id = ? AND brand_contact_id = ?",
                profile.getTenantId(), brandContactId);
        if (removed == 1) jdbc.update("UPDATE creator_public_profile SET follower_count = GREATEST(follower_count - 1, 0) WHERE tenant_id = ?",
                profile.getTenantId());
        return state(profile.getTenantId(), false);
    }

    /** Confirmed followers of a creator, for the "new film" notice (Phase D). */
    public List<UUID> followers(UUID tenantId) {
        return jdbc.queryForList("SELECT brand_contact_id FROM creator_follower WHERE tenant_id = ?", UUID.class, tenantId);
    }

    private FollowState state(UUID tenantId, boolean following) {
        Integer count = jdbc.queryForObject("SELECT follower_count FROM creator_public_profile WHERE tenant_id = ?",
                Integer.class, tenantId);
        return new FollowState(following, count == null ? 0 : count);
    }

    private CreatorPublicProfile activeProfile(String handle) {
        return profileRepository.findByHandle(handle)
                .filter(p -> p.getStatus() == ProfileStatus.ACTIVE)
                .orElseThrow(() -> new IllegalArgumentException("Unknown creator"));
    }
}
