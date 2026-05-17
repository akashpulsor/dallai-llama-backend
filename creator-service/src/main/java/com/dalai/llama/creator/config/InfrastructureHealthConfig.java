package com.dalai.llama.creator.config;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import software.amazon.awssdk.services.s3.S3Client;

import java.util.Properties;
import java.util.concurrent.TimeUnit;

@Configuration
public class InfrastructureHealthConfig {

    @Bean(destroyMethod = "close")
    public AdminClient creatorKafkaAdminClient(@Value("${spring.kafka.bootstrap-servers}") String bootstrapServers) {
        Properties properties = new Properties();
        properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        properties.put(AdminClientConfig.CLIENT_ID_CONFIG, "creator-service-health");
        return AdminClient.create(properties);
    }

    @Bean
    public HealthIndicator kafkaHealthIndicator(AdminClient creatorKafkaAdminClient) {
        return () -> {
            try {
                String clusterId = creatorKafkaAdminClient.describeCluster().clusterId().get(2, TimeUnit.SECONDS);
                return Health.up().withDetail("clusterId", clusterId).build();
            } catch (Exception ex) {
                return Health.down(ex).build();
            }
        };
    }

    @Bean
    public HealthIndicator creatorObjectStorageHealthIndicator(S3Client s3Client, CreatorProperties properties) {
        return () -> {
            try {
                s3Client.listBuckets();
                return Health.up()
                        .withDetail("endpoint", properties.getStorage().getEndpoint())
                        .withDetail("assetsBucket", properties.getStorage().getCreatorAssetsBucket())
                        .withDetail("exportsBucket", properties.getStorage().getCreatorExportsBucket())
                        .build();
            } catch (Exception ex) {
                return Health.down(ex)
                        .withDetail("endpoint", properties.getStorage().getEndpoint())
                        .build();
            }
        };
    }
}
