package com.modelrag.toolgateway.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.toolgateway.catalog.ToolExecutionSpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.InMemoryDnsResolver;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

/** Hardened HTTP adapter; credentials are accepted only from gateway execution specs. */
@Service
@Profile("!test")
public class HttpToolInvoker {
    private final ConcurrentHashMap<Thread, CloseableHttpClient> activeClients = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final long timeoutMillis;
    private final boolean allowHttpLocalhost;
    private final Set<String> allowedHosts;

    public HttpToolInvoker(ObjectMapper json, @Value("${modelrag.tools.http-timeout-ms:5000}") long timeoutMillis,
            @Value("${modelrag.tools.allow-http-localhost:false}") boolean allowHttpLocalhost,
            @Value("${modelrag.tools.allowed-hosts:}") String allowedHosts) {
        this.json = json;
        this.timeoutMillis = Math.max(100, Math.min(5000, timeoutMillis));
        this.allowHttpLocalhost = allowHttpLocalhost;
        this.allowedHosts = Arrays.stream(allowedHosts == null ? new String[0] : allowedHosts.split(","))
                .map(String::trim).map(String::toLowerCase).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public String invoke(ToolExecutionSpec spec, String input) {
        return invoke(spec, input, null);
    }

    public String invoke(ToolExecutionSpec spec, String input, String idempotencyKey) {
        if (spec == null || !spec.descriptor().http()) {
            throw new IllegalArgumentException("不是 HTTP 工具");
        }
        if (spec.descriptor().idempotent() && (idempotencyKey == null || idempotencyKey.isBlank())) {
            throw new IllegalArgumentException("幂等 HTTP 工具必须提供 idempotency key");
        }
        if (spec.descriptor().hasAuthSecret()
                && (spec.authHeaderValue() == null || spec.authHeaderValue().isBlank())) {
            throw new IllegalStateException("HTTP 工具认证密钥无法解析，已拒绝执行");
        }
        if (spec.authHeaderValue() != null
                && (spec.authHeaderValue().indexOf('\r') >= 0 || spec.authHeaderValue().indexOf('\n') >= 0)) {
            throw new IllegalArgumentException("HTTP 工具认证 Header 值不合法");
        }
        try {
            URI endpoint = URI.create(spec.descriptor().endpoint());
            InetAddress[] firstResolution = validateEndpoint(endpoint);
            String body = json.writeValueAsString(Map.of("tool", spec.descriptor().name(), "input", input));
            HttpPost request = new HttpPost(endpoint);
            request.setEntity(new StringEntity(body, org.apache.hc.core5.http.ContentType.APPLICATION_JSON));
            if (idempotencyKey != null && !idempotencyKey.isBlank()) request.setHeader("Idempotency-Key", idempotencyKey);
            if (spec.descriptor().authHeaderName() != null && spec.authHeaderValue() != null) {
                request.setHeader(spec.descriptor().authHeaderName(), spec.authHeaderValue());
            }
            InetAddress[] pinnedResolution = resolveAllowed(endpoint.getHost());
            if (!addresses(firstResolution).equals(addresses(pinnedResolution))) {
                throw new IllegalArgumentException("HTTP 工具 DNS 解析结果在调用前发生变化");
            }
            return executePinned(endpoint.getHost(), pinnedResolution, request);
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("HTTP 工具调用失败", error);
        }
    }

    private String executePinned(String host, InetAddress[] addresses, HttpPost request) throws Exception {
        InMemoryDnsResolver resolver = new InMemoryDnsResolver();
        resolver.add(host, addresses);
        var manager = PoolingHttpClientConnectionManagerBuilder.create().setDnsResolver(resolver).build();
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(timeoutMillis))
                .setConnectionRequestTimeout(Timeout.ofMilliseconds(timeoutMillis))
                .setResponseTimeout(Timeout.ofMilliseconds(timeoutMillis)).build();
        Thread caller = Thread.currentThread();
        try (CloseableHttpClient client = HttpClients.custom().setConnectionManager(manager)
                .setDefaultRequestConfig(requestConfig).disableRedirectHandling().disableAutomaticRetries().build()) {
            activeClients.put(caller, client);
            return client.execute(request, response -> {
                int status = response.getCode();
                if (status / 100 != 2) throw new IllegalStateException("HTTP 工具返回状态码 " + status);
                if (response.getEntity() == null) return "";
                try (var stream = response.getEntity().getContent()) {
                    byte[] responseBody = stream.readNBytes(1_048_577);
                    if (responseBody.length > 1_048_576) throw new IllegalStateException("HTTP 工具响应超过 1MiB");
                    return new String(responseBody, StandardCharsets.UTF_8);
                }
            });
        } finally {
            activeClients.remove(caller);
        }
    }

    /** Cancels an in-flight HTTP exchange owned by the supplied worker. */
    public boolean cancel(Thread worker) {
        CloseableHttpClient client = worker == null ? null : activeClients.remove(worker);
        if (client == null) return false;
        client.close(org.apache.hc.core5.io.CloseMode.IMMEDIATE);
        return true;
    }

    private InetAddress[] validateEndpoint(URI endpoint) {
        if (!"https".equalsIgnoreCase(endpoint.getScheme())
                && !(allowHttpLocalhost && "http".equalsIgnoreCase(endpoint.getScheme()) && isLoopback(endpoint.getHost()))) {
            throw new IllegalArgumentException("HTTP 工具只允许 HTTPS endpoint");
        }
        if (endpoint.getUserInfo() != null || endpoint.getHost() == null || endpoint.getPort() == 0
                || endpoint.getQuery() != null || endpoint.getFragment() != null) {
            throw new IllegalArgumentException("HTTP 工具 endpoint 不合法");
        }
        String host = endpoint.getHost().toLowerCase();
        if (!(allowHttpLocalhost && isLoopback(host)) && !allowedHosts.contains(host)) {
            throw new IllegalArgumentException("HTTP 工具域名不在 allowlist");
        }
        if (isMetadataHost(host)) throw new IllegalArgumentException("HTTP 工具禁止访问内网或 metadata 地址");
        return resolveAllowed(host);
    }

    private boolean isLoopback(String host) {
        try { return InetAddress.getByName(host).isLoopbackAddress(); }
        catch (UnknownHostException error) { return false; }
    }

    private boolean isMetadataHost(String host) {
        return "metadata.google.internal".equalsIgnoreCase(host) || "169.254.169.254".equals(host)
                || "100.100.100.200".equals(host);
    }

    private InetAddress[] resolveAllowed(String host) {
        try {
            InetAddress[] values = InetAddress.getAllByName(host);
            if (values.length == 0) throw new IllegalArgumentException("HTTP 工具域名无法解析");
            for (InetAddress address : values) {
                if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                        || address.isLoopbackAddress() || address.isMulticastAddress() || isIpv6UniqueLocal(address)
                        || isCarrierGradeNat(address)) {
                    if (!(allowHttpLocalhost && address.isLoopbackAddress())) {
                        throw new IllegalArgumentException("HTTP 工具 DNS 解析到受限地址");
                    }
                }
            }
            return values;
        } catch (UnknownHostException error) {
            throw new IllegalArgumentException("HTTP 工具域名无法解析", error);
        }
    }

    private boolean isIpv6UniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private boolean isCarrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

    private Set<String> addresses(InetAddress[] values) {
        Set<String> result = new HashSet<>();
        for (InetAddress value : values) result.add(value.getHostAddress());
        return Set.copyOf(result);
    }
}
