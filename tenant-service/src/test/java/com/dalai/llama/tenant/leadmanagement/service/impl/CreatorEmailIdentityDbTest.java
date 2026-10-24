package com.dalai.llama.tenant.leadmanagement.service.impl;

import com.dalai.llama.tenant.leadmanagement.config.LeadManagementProperties;
import com.dalai.llama.tenant.leadmanagement.domain.entity.CreatorEmailIdentity;
import com.dalai.llama.tenant.leadmanagement.service.CreatorEmailIdentityService;
import com.dalai.llama.tenant.showcase.ShowcaseDbTestSupport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Partner addresses on a real Postgres: creators with the same name provisioned at the same
 * moment each get their own address (asha, asha2, ...), never a UNIQUE(email) failure. */
@Testcontainers(disabledWithoutDocker = true)
@DataJpaTest(properties = {"spring.jpa.hibernate.ddl-auto=validate", "spring.flyway.enabled=true",
        "lead-management.domain=partner.dalaillama.in"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@EnableConfigurationProperties(LeadManagementProperties.class)
@Import(CreatorEmailIdentityServiceImpl.class)
class CreatorEmailIdentityDbTest extends ShowcaseDbTestSupport {

    @Autowired
    private CreatorEmailIdentityService service;
    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM creator_email_identity");
    }

    @Test
    void sameNameAtTheSameMomentGetsDistinctAddresses() throws Exception {
        List<UUID> tenants = IntStream.range(0, 4).mapToObj(i -> tenant(jdbc, "Asha " + i)).toList();
        ExecutorService pool = Executors.newFixedThreadPool(tenants.size());
        try {
            List<Callable<CreatorEmailIdentity>> calls = tenants.stream()
                    .<Callable<CreatorEmailIdentity>>map(id -> () -> service.provisionForCreator(id, "Asha")).toList();
            List<String> emails = pool.invokeAll(calls).stream().map(CreatorEmailIdentityDbTest::get).map(CreatorEmailIdentity::getEmail).toList();

            assertThat(emails).containsExactlyInAnyOrder("asha@partner.dalaillama.in", "asha2@partner.dalaillama.in",
                    "asha3@partner.dalaillama.in", "asha4@partner.dalaillama.in");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void legacyAddressBecomesReadableAndThenStays() {
        UUID tenantId = tenant(jdbc, "Ravi");
        String legacy = "cr_" + tenantId.toString().replace("-", "");
        jdbc.update("INSERT INTO creator_email_identity (id, tenant_id, local_part, email, display_name, status) VALUES (?, ?, ?, ?, ?, 'PROVISIONED')",
                UUID.randomUUID(), tenantId, legacy, legacy + "@partner.dalaillama.in", "Ravi Kumar");

        assertThat(service.provisionForCreator(tenantId, "Ravi Kumar").getEmail()).isEqualTo("ravi.kumar@partner.dalaillama.in");
        assertThat(service.provisionForCreator(tenantId, "Ravi K Films").getEmail()).isEqualTo("ravi.kumar@partner.dalaillama.in");
    }

    private static <T> T get(Future<T> future) {
        try {
            return future.get();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
