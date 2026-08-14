package com.modelrag.knowledge.service;

import java.io.IOException;
import java.io.InputStream;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;

@Service
@Profile("!test")
public class S3ObjectStorageService implements ObjectStorageService {
    private final S3Client client;
    private final String bucket;

    public S3ObjectStorageService(S3Client client,
            @org.springframework.beans.factory.annotation.Value("${modelrag.storage.bucket:modelrag}") String bucket) {
        this.client = client;
        this.bucket = bucket;
    }

    @Override
    public void put(String objectKey, InputStream content, long contentLength, String contentType) throws IOException {
        try (content) {
            PutObjectRequest request = PutObjectRequest.builder()
                    .bucket(bucket)
                    .key(objectKey)
                    .contentType(contentType == null ? "application/octet-stream" : contentType)
                    .contentLength(contentLength)
                    .build();
            client.putObject(request, RequestBody.fromInputStream(content, contentLength));
        }
    }

    @Override
    public InputStream open(String objectKey) {
        return client.getObject(GetObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }

    @Override
    public void delete(String objectKey) {
        client.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(objectKey).build());
    }
}
