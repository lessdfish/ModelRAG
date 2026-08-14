package com.modelrag.knowledge.config;

import java.net.URI;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

@Configuration
@Profile("!test")
public class ObjectStorageConfig {
    @Bean
    S3Client s3Client(
            @Value("${modelrag.storage.endpoint:http://localhost:19000}") String endpoint,
            @Value("${modelrag.storage.region:us-east-1}") String region,
            @Value("${modelrag.storage.access-key:${MODELRAG_S3_ACCESS_KEY:}}") String accessKey,
            @Value("${modelrag.storage.secret-key:${MODELRAG_S3_SECRET_KEY:}}") String secretKey) {
        if (accessKey.isBlank() || secretKey.isBlank()) {
            throw new IllegalStateException("对象存储凭据未配置");
        }
        return S3Client.builder()
                .endpointOverride(URI.create(endpoint))
                .region(Region.of(region))
                .forcePathStyle(true)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey)))
                .build();
    }
}
