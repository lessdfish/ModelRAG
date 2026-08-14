package com.modelrag.server.health;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.HeadBucketRequest;

/** The source-document bucket is required before this instance can accept production traffic. */
@Component("objectStorage")
@Profile("!test")
public class ObjectStorageHealthIndicator implements HealthIndicator {
    private final S3Client client;
    private final String bucket;

    public ObjectStorageHealthIndicator(S3Client client,
            @Value("${modelrag.storage.bucket:modelrag}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public Health health() {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build());
            return Health.up().withDetail("bucket", bucket).build();
        } catch (RuntimeException error) {
            return Health.down(error).withDetail("bucket", bucket).build();
        }
    }
}
