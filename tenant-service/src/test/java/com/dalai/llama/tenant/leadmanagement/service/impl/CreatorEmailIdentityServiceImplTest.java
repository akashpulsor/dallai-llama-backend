package com.dalai.llama.tenant.leadmanagement.service.impl;

import com.dalai.llama.tenant.leadmanagement.config.LeadManagementProperties;
import com.dalai.llama.tenant.leadmanagement.domain.CreatorEmailIdentityStatus;
import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.repository.CreatorEmailIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Guards the deterministic creator-email-identity provisioning. Key contract points:
 *   1. same tenantId always maps to the same email address (immutable UUID → immutable local
 *      part; a display-name change must never rewrite the email string);
 *   2. the provisioning call is idempotent (safe to re-drive from a re-delivered
 *      subscription.activated Kafka event);
 *   3. a UNIQUE-constraint race between two concurrent activators for the same tenant is
 *      recovered by re-reading, not by surfacing DataIntegrityViolationException up the stack. */
class CreatorEmailIdentityServiceImplTest {

    private static final String DOMAIN = "partner.dalaillama.in";

    private CreatorEmailIdentityRepository repository;
    private CreatorEmailIdentityServiceImpl service;

    @BeforeEach
    void setUp() {
        repository = mock(CreatorEmailIdentityRepository.class);
        LeadManagementProperties props = new LeadManagementProperties(DOMAIN, "unused-here", 1024L, 300L);
        service = new CreatorEmailIdentityServiceImpl(repository, props);
    }

    @Test
    void computeEmail_isDeterministicFromTenantId() {
        UUID tenantId = UUID.fromString("8f42c91a-62f8-4e53-a971-9a73c10f1001");
        // Local part is UUID with hyphens stripped, lower-case, prefixed with cr_. This exact
        // spec is baked into the migration comment and mirrored in the entity's local_part
        // column; if this test fails, at least one of those two documents is now lying.
        assertThat(service.computeEmail(tenantId))
                .isEqualTo("cr_8f42c91a62f84e53a9719a73c10f1001@partner.dalaillama.in");
    }

    @Test
    void computeEmail_sameInputSameOutput() {
        UUID tenantId = UUID.randomUUID();
        assertThat(service.computeEmail(tenantId)).isEqualTo(service.computeEmail(tenantId));
    }

    @Test
    void provisionForCreator_createsRowWhenNoneExists() {
        UUID tenantId = UUID.fromString("11111111-2222-3333-4444-555555555555");
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(repository.save(any(CreatorEmailIdentity.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatorEmailIdentity out = service.provisionForCreator(tenantId, "Akash Tripathi");

        assertThat(out.getEmail()).isEqualTo("cr_11111111222233334444555555555555@partner.dalaillama.in");
        assertThat(out.getLocalPart()).isEqualTo("cr_11111111222233334444555555555555");
        assertThat(out.getDisplayName()).isEqualTo("Akash Tripathi");
        assertThat(out.getStatus()).isEqualTo(CreatorEmailIdentityStatus.PROVISIONED);
        assertThat(out.getTenantId()).isEqualTo(tenantId);
        verify(repository, times(1)).save(any());
    }

    @Test
    void provisionForCreator_isIdempotentAndDoesNotRewriteEmailOnRename() {
        UUID tenantId = UUID.randomUUID();
        CreatorEmailIdentity existing = CreatorEmailIdentity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .localPart("cr_originalabcd")
                .email("cr_originalabcd@partner.dalaillama.in")
                .displayName("Original Name")
                .status(CreatorEmailIdentityStatus.PROVISIONED)
                .build();
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));
        when(repository.save(any(CreatorEmailIdentity.class))).thenAnswer(inv -> inv.getArgument(0));

        CreatorEmailIdentity out = service.provisionForCreator(tenantId, "Renamed Creator");

        // Renaming updates display_name; email/local_part stay put -- the whole point of
        // pinning the address to the immutable UUID.
        assertThat(out.getEmail()).isEqualTo("cr_originalabcd@partner.dalaillama.in");
        assertThat(out.getLocalPart()).isEqualTo("cr_originalabcd");
        assertThat(out.getDisplayName()).isEqualTo("Renamed Creator");
        verify(repository, times(1)).save(existing);
    }

    @Test
    void provisionForCreator_skipsSaveWhenDisplayNameUnchanged() {
        UUID tenantId = UUID.randomUUID();
        CreatorEmailIdentity existing = CreatorEmailIdentity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .localPart("cr_x")
                .email("cr_x@partner.dalaillama.in")
                .displayName("Same Name")
                .status(CreatorEmailIdentityStatus.PROVISIONED)
                .build();
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.of(existing));

        service.provisionForCreator(tenantId, "Same Name");

        // No display-name delta, no updated_at churn -- verified by the absence of save().
        verify(repository, never()).save(any());
    }

    @Test
    void provisionForCreator_recoversFromUniqueConstraintRace() {
        UUID tenantId = UUID.randomUUID();
        CreatorEmailIdentity winner = CreatorEmailIdentity.builder()
                .id(UUID.randomUUID())
                .tenantId(tenantId)
                .localPart("cr_race")
                .email("cr_race@partner.dalaillama.in")
                .displayName("First Writer")
                .status(CreatorEmailIdentityStatus.PROVISIONED)
                .build();
        // First lookup misses; another thread wins the insert; our save() blows up on the
        // UNIQUE(tenant_id) constraint; we re-read and return the row the winner wrote.
        when(repository.findByTenantId(tenantId))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(winner));
        when(repository.save(any(CreatorEmailIdentity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate tenant_id"));

        CreatorEmailIdentity out = service.provisionForCreator(tenantId, "Second Writer");

        assertThat(out).isSameAs(winner);
        verify(repository, times(2)).findByTenantId(tenantId);
    }

    @Test
    void provisionForCreator_propagatesRaceExceptionWhenReReadAlsoMisses() {
        // Sanity check: if the DIVE is real (not a race) -- e.g. a UNIQUE(email) collision
        // from a bad manual insert -- the re-read still misses and we surface the original
        // exception rather than silently swallowing corruption.
        UUID tenantId = UUID.randomUUID();
        when(repository.findByTenantId(tenantId)).thenReturn(Optional.empty());
        when(repository.save(any(CreatorEmailIdentity.class)))
                .thenThrow(new DataIntegrityViolationException("something else"));

        assertThat(org.junit.jupiter.api.Assertions.assertThrows(DataIntegrityViolationException.class,
                () -> service.provisionForCreator(tenantId, "x")))
                .hasMessageContaining("something else");
    }
}
