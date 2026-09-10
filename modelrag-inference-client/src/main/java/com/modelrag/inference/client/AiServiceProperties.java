package com.modelrag.inference.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Bounded, non-business configuration for the stateless AI compute plane. */
@Component
public final class AiServiceProperties {
    private final boolean enabled;
    private final String baseUrl;
    private final String authToken;
    private final long connectTimeoutMs;
    private final long requestTimeoutMs;
    private final int maxConcurrentRequests;
    private final long maxJsonResponseBytes;
    private final long maxUploadBytes;

    @Autowired
    public AiServiceProperties(
            @Value("${modelrag.inference.enabled:false}") Boolean enabled,
            @Value("${modelrag.inference.base-url:http://127.0.0.1:18180}") String baseUrl,
            @Value("${modelrag.inference.auth-token:}") String authToken,
            @Value("${modelrag.inference.connect-timeout-ms:500}") Long connectTimeoutMs,
            @Value("${modelrag.inference.request-timeout-ms:30000}") Long requestTimeoutMs,
            @Value("${modelrag.inference.max-concurrent-requests:32}") Integer maxConcurrentRequests,
            @Value("${modelrag.inference.max-json-response-bytes:16777216}") Long maxJsonResponseBytes,
            @Value("${modelrag.inference.max-upload-bytes:52428800}") Long maxUploadBytes) {
        this(enabled != null && enabled, baseUrl, authToken,
                connectTimeoutMs == null ? 500 : connectTimeoutMs,
                requestTimeoutMs == null ? 30_000 : requestTimeoutMs,
                maxConcurrentRequests == null ? 32 : maxConcurrentRequests,
                maxJsonResponseBytes == null ? 16L * 1024 * 1024 : maxJsonResponseBytes,
                maxUploadBytes == null ? 50L * 1024 * 1024 : maxUploadBytes);
    }

    public AiServiceProperties(boolean enabled, String baseUrl, String authToken, long connectTimeoutMs,
            long requestTimeoutMs, int maxConcurrentRequests, long maxJsonResponseBytes, long maxUploadBytes) {
        this(enabled, baseUrl, authToken, connectTimeoutMs, requestTimeoutMs, maxConcurrentRequests,
                maxJsonResponseBytes, maxUploadBytes, true);
    }

    private AiServiceProperties(boolean enabled, String baseUrl, String authToken, long connectTimeoutMs,
            long requestTimeoutMs, int maxConcurrentRequests, long maxJsonResponseBytes, long maxUploadBytes,
            boolean ignored) {
        this.enabled = enabled;
        this.baseUrl = normalizeBaseUrl(baseUrl);
        this.authToken = authToken == null ? "" : authToken.trim();
        this.connectTimeoutMs = bounded(connectTimeoutMs, 1, 60_000);
        this.requestTimeoutMs = bounded(requestTimeoutMs, 1, 600_000);
        this.maxConcurrentRequests = (int) bounded(maxConcurrentRequests, 1, 1_024);
        this.maxJsonResponseBytes = bounded(maxJsonResponseBytes, 1, 64L * 1024 * 1024);
        this.maxUploadBytes = bounded(maxUploadBytes, 1, 512L * 1024 * 1024);
    }

    public AiServiceProperties() {
        this(false, "http://127.0.0.1:18180", "", 500, 30_000, 32,
                16L * 1024 * 1024, 50L * 1024 * 1024);
    }

    public boolean enabled() { return enabled; }
    public String baseUrl() { return baseUrl; }
    public String authToken() { return authToken; }
    public long connectTimeoutMs() { return connectTimeoutMs; }
    public long requestTimeoutMs() { return requestTimeoutMs; }
    public int maxConcurrentRequests() { return maxConcurrentRequests; }
    public long maxJsonResponseBytes() { return maxJsonResponseBytes; }
    public long maxUploadBytes() { return maxUploadBytes; }

    private static long bounded(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static String normalizeBaseUrl(String value) {
        String result = value == null || value.isBlank() ? "http://127.0.0.1:18180" : value.trim();
        if (!result.startsWith("http://") && !result.startsWith("https://")) result = "http://" + result;
        return result.replaceAll("/+$", "");
    }
}
