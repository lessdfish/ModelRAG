package com.modelrag.inference.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** The only low-level HTTP transport used by Java AI compute adapters. */
@Service
public class AiServiceClient {
    private final AiServiceProperties properties;
    private final ObjectMapper json;
    private final MeterRegistry metrics;
    private final HttpClient http;
    private final Semaphore concurrency;

    @Autowired
    public AiServiceClient(AiServiceProperties properties, ObjectMapper json, MeterRegistry metrics) {
        this.properties = properties == null ? new AiServiceProperties() : properties;
        this.json = json == null ? new ObjectMapper() : json;
        this.metrics = metrics == null ? new SimpleMeterRegistry() : metrics;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(this.properties.connectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        this.concurrency = new Semaphore(this.properties.maxConcurrentRequests());
    }

    public AiServiceClient(AiServiceProperties properties, ObjectMapper json) {
        this(properties, json, null);
    }

    public AiServiceClient(AiServiceProperties properties) {
        this(properties, new ObjectMapper(), null);
    }

    public <T> T postJson(String operation, String path, Object request, Class<T> responseType,
            Duration timeout) {
        return postJson(operation, path, request, responseType, timeout, UUID.randomUUID().toString());
    }

    public <T> T postJson(String operation, String path, Object request, Class<T> responseType,
            Duration timeout, String requestId) {
        byte[] body;
        try {
            body = json.writeValueAsBytes(request);
        } catch (JsonProcessingException error) {
            throw new AiServiceException("AI 请求编码失败", 400, false);
        }
        HttpRequest.Builder builder = requestBuilder(operation, path, timeout, requestId)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        return send(operation, builder, responseType);
    }

    /** Streams one source file as multipart without materializing it as JSON or a byte array. */
    public <T> T postMultipart(String operation, String path, Path source, String logicalFileName,
            String contentType, Map<String, String> fields, Class<T> responseType, Duration timeout) {
        if (source == null || !Files.isRegularFile(source)) {
            throw new AiServiceException("AI 文件不存在", 400, false);
        }
        try {
            long fileSize = Files.size(source);
            if (fileSize > properties.maxUploadBytes()) {
                throw new AiServiceException("AI 文件超过大小上限", 413, false);
            }
            String boundary = "----modelrag-" + UUID.randomUUID();
            byte[] prefix = multipartPrefix(boundary, fields, logicalFileName, contentType);
            byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            HttpRequest.BodyPublisher publisher = HttpRequest.BodyPublishers.ofInputStream(() -> {
                try {
                    return new java.io.SequenceInputStream(
                            new java.io.SequenceInputStream(new ByteArrayInputStream(prefix),
                                    new LimitedInputStream(Files.newInputStream(source), properties.maxUploadBytes())),
                            new ByteArrayInputStream(suffix));
                } catch (IOException error) {
                    throw new java.io.UncheckedIOException(error);
                }
            });
            HttpRequest.Builder builder = requestBuilder(operation, path, timeout, UUID.randomUUID().toString())
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(publisher);
            return send(operation, builder, responseType);
        } catch (IOException error) {
            throw new AiServiceException("AI 文件读取失败", 400, false);
        }
    }

    private byte[] multipartPrefix(String boundary, Map<String, String> fields, String logicalFileName,
            String contentType) {
        StringBuilder result = new StringBuilder();
        if (fields != null) {
            for (Map.Entry<String, String> entry : fields.entrySet()) {
                result.append("--").append(boundary).append("\r\n")
                        .append("Content-Disposition: form-data; name=\"")
                        .append(headerValue(entry.getKey())).append("\"\r\n\r\n")
                        .append(fieldValue(entry.getValue())).append("\r\n");
            }
        }
        result.append("--").append(boundary).append("\r\n")
                .append("Content-Disposition: form-data; name=\"file\"; filename=\"")
                .append(fileName(logicalFileName)).append("\"\r\n")
                .append("Content-Type: ").append(headerValue(contentType == null ? "application/octet-stream" : contentType))
                .append("\r\n\r\n");
        return result.toString().getBytes(StandardCharsets.UTF_8);
    }

    private HttpRequest.Builder requestBuilder(String operation, String path, Duration timeout, String requestId) {
        if (!properties.enabled()) throw new AiServiceException("AI 服务未启用", 503, false);
        if (properties.authToken().isBlank()) throw new AiServiceException("AI 服务认证未配置", 503, false);
        Duration effective = effectiveTimeout(timeout);
        String id = requestId == null || requestId.isBlank() ? UUID.randomUUID().toString() : requestId.trim();
        return HttpRequest.newBuilder(uri(path)).timeout(effective)
                .header("Authorization", "Bearer " + properties.authToken())
                .header("X-Request-Id", id)
                .header("Accept", "application/json");
    }

    private <T> T send(String operation, HttpRequest.Builder builder, Class<T> responseType) {
        if (!concurrency.tryAcquire()) throw new AiServiceException("AI 服务并发已满", 429, true);
        long started = System.nanoTime();
        String metricOperation = safeTag(operation);
        metrics.counter("modelrag.inference.requests", "operation", metricOperation).increment();
        try {
            HttpResponse<byte[]> response = http.send(builder.build(), boundedBodyHandler());
            int status = response.statusCode();
            if (status < 200 || status >= 300) {
                throw new AiServiceException("AI 服务请求失败: " + status, status, retryableStatus(status));
            }
            byte[] body = response.body() == null ? new byte[0] : response.body();
            metrics.counter("modelrag.inference.response_bytes", "operation", metricOperation)
                    .increment(body.length);
            if (responseType == null || responseType == Void.class) return null;
            try {
                return json.readValue(body, responseType);
            } catch (Exception error) {
                throw new AiServiceException("AI 服务响应格式无效", status, false);
            }
        } catch (AiServiceException error) {
            recordFailure(metricOperation, error.statusCode());
            throw error;
        } catch (HttpTimeoutException error) {
            recordFailure(metricOperation, 504);
            throw new AiServiceException("AI 服务请求超时", 504, true);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            recordFailure(metricOperation, 499);
            throw new AiServiceException("AI 服务请求被中断", 499, false);
        } catch (IOException error) {
            AiServiceException failure = nestedAiFailure(error);
            if (failure != null) {
                recordFailure(metricOperation, failure.statusCode());
                throw failure;
            }
            recordFailure(metricOperation, 503);
            throw new AiServiceException("AI 服务连接失败", 503, true);
        } catch (CompletionException error) {
            AiServiceException failure = nestedAiFailure(error);
            if (failure != null) {
                recordFailure(metricOperation, failure.statusCode());
                throw failure;
            }
            recordFailure(metricOperation, 503);
            throw new AiServiceException("AI 服务连接失败", 503, true);
        } catch (RuntimeException error) {
            recordFailure(metricOperation, 503);
            throw new AiServiceException("AI 服务请求失败", 503, true);
        } finally {
            concurrency.release();
            metrics.timer("modelrag.inference.latency", "operation", metricOperation)
                    .record(System.nanoTime() - started, TimeUnit.NANOSECONDS);
        }
    }

    private HttpResponse.BodyHandler<byte[]> boundedBodyHandler() {
        return ignored -> new BoundedBodySubscriber(properties.maxJsonResponseBytes());
    }

    private URI uri(String path) {
        String suffix = path == null || path.isBlank() ? "/" : (path.startsWith("/") ? path : "/" + path);
        try {
            return URI.create(properties.baseUrl() + suffix);
        } catch (IllegalArgumentException error) {
            throw new AiServiceException("AI 服务地址无效", 400, false);
        }
    }

    private Duration effectiveTimeout(Duration requested) {
        long requestedMs = requested == null ? properties.requestTimeoutMs() : requested.toMillis();
        if (requestedMs <= 0) throw new AiServiceException("AI 请求超时预算无效", 408, false);
        return Duration.ofMillis(Math.max(1, Math.min(requestedMs, properties.requestTimeoutMs())));
    }

    private boolean retryableStatus(int status) {
        return status == 429 || status == 502 || status == 503 || status == 504;
    }

    private void recordFailure(String operation, int status) {
        metrics.counter("modelrag.inference.failures", "operation", operation,
                "status", Integer.toString(status)).increment();
    }

    private AiServiceException nestedAiFailure(Throwable error) {
        Throwable current = error;
        for (int depth = 0; current != null && depth++ < 4; depth++) {
            if (current instanceof AiServiceException failure) return failure;
            current = current.getCause();
        }
        return null;
    }

    private String safeTag(String value) {
        if (value == null || value.isBlank()) return "unknown";
        return value.replaceAll("[^a-zA-Z0-9_.-]", "_").substring(0, Math.min(40, value.length()));
    }

    private String headerValue(String value) {
        return (value == null ? "" : value).replace('\r', '_').replace('\n', '_').replace('"', '_');
    }

    private String fieldValue(String value) {
        return (value == null ? "" : value).replace("\r\n", "\n").replace('\r', '\n');
    }

    private String fileName(String value) {
        String safe = value == null || value.isBlank() ? "document.bin" : value;
        safe = safe.replace('\\', '_').replace('/', '_');
        return headerValue(safe);
    }

    private static final class LimitedInputStream extends FilterInputStream {
        private final long limit;
        private long read;

        private LimitedInputStream(InputStream input, long limit) {
            super(input);
            this.limit = limit;
        }

        @Override public int read() throws IOException {
            int value = super.read();
            if (value >= 0 && ++read > limit) throw new IOException("upload exceeds limit");
            return value;
        }

        @Override public int read(byte[] bytes, int offset, int length) throws IOException {
            int count = super.read(bytes, offset, length);
            if (count > 0) {
                read += count;
                if (read > limit) throw new IOException("upload exceeds limit");
            }
            return count;
        }
    }

    private static final class BoundedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final long limit;
        private final ByteArrayOutputStream output = new ByteArrayOutputStream();
        private final java.util.concurrent.CompletableFuture<byte[]> result = new java.util.concurrent.CompletableFuture<>();
        private Flow.Subscription subscription;
        private long size;

        private BoundedBodySubscriber(long limit) { this.limit = limit; }

        @Override public java.util.concurrent.CompletionStage<byte[]> getBody() { return result; }

        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override public void onNext(List<ByteBuffer> buffers) {
            if (result.isDone()) return;
            try {
                for (ByteBuffer buffer : buffers) {
                    ByteBuffer copy = buffer.duplicate();
                    long next = size + copy.remaining();
                    if (next > limit) {
                        subscription.cancel();
                        result.completeExceptionally(new AiServiceException("AI 响应超过大小上限", 413, false));
                        return;
                    }
                    byte[] bytes = new byte[copy.remaining()];
                    copy.get(bytes);
                    output.write(bytes);
                    size = next;
                }
            } catch (IOException error) {
                result.completeExceptionally(new AiServiceException("AI 响应读取失败", 503, true));
            }
        }

        @Override public void onError(Throwable throwable) {
            if (throwable instanceof AiServiceException failure) result.completeExceptionally(failure);
            else result.completeExceptionally(new AiServiceException("AI 响应读取失败", 503, true));
        }

        @Override public void onComplete() {
            result.complete(output.toByteArray());
        }
    }
}
