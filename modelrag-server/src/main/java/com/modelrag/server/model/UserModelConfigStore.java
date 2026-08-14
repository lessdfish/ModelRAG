package com.modelrag.server.model;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.api.ModelConfigRequest;
import com.modelrag.api.SecretProtector;
import com.modelrag.api.UserModelProvider;
import com.modelrag.api.ChatModelProviderFactory;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.rate.DatasetRateLimiter;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** User-scoped BYOK configuration. Ciphertext never crosses the controller boundary. */
@Service
@Profile("!test")
public class UserModelConfigStore implements UserModelProvider, ChatModelProviderFactory {
    public record Config(String id, String modelType, String provider, String modelName, String baseUrl,
            String secretId, boolean enabled, String capabilityStatus, List<String> capabilities,
            boolean hasApiKey, String apiKeyHint, String updateTime) {}
    public record Validation(String id, String status, String message, List<String> capabilities) {}
    private record Stored(String id, String userId, String modelType, String provider, String modelName,
            String baseUrl, String secretId, String encryptedKey, boolean enabled,
            String capabilityStatus, List<String> capabilities) {}

    private final JdbcTemplate jdbc;
    private final SecretProtector secrets;
    private final ObjectMapper json;
    private final SpringAiByokChatModels nativeModels;
    private final CancellableModelInvoker modelInvoker;
    private final DatasetRateLimiter limiter;
    private final Set<String> allowedHosts;

    private static final Set<String> PROVIDERS = Set.of("openai", "openai-compatible", "anthropic", "gemini",
            "google", "deepseek", "ollama", "qwen", "zhipu", "kimi", "doubao", "minimax", "groq", "vllm");
    private static final Map<String, String> DEFAULT_URLS = Map.of(
            "openai", "https://api.openai.com",
            "anthropic", "https://api.anthropic.com",
            "gemini", "https://generativelanguage.googleapis.com",
            "google", "https://generativelanguage.googleapis.com",
            "deepseek", "https://api.deepseek.com",
            "ollama", "http://127.0.0.1:11434");
    private static final Set<String> OFFICIAL_HOSTS = Set.of("api.openai.com", "api.anthropic.com",
            "generativelanguage.googleapis.com", "api.deepseek.com");

    public UserModelConfigStore(JdbcTemplate jdbc, SecretProtector secrets, ObjectMapper json,
            SpringAiByokChatModels nativeModels, CancellableModelInvoker modelInvoker, DatasetRateLimiter limiter,
            @Value("${modelrag.models.allowed-hosts:}") String allowedHosts) {
        this.jdbc = jdbc;
        this.secrets = secrets;
        this.json = json;
        this.nativeModels = nativeModels;
        this.modelInvoker = modelInvoker;
        this.limiter = limiter;
        this.allowedHosts = Arrays.stream(allowedHosts == null ? new String[0] : allowedHosts.split(","))
                .map(String::trim).map(value -> value.toLowerCase(Locale.ROOT)).filter(value -> !value.isBlank())
                .collect(Collectors.toUnmodifiableSet());
    }

    public List<Config> list(String userId) {
        return jdbc.query("""
                SELECT id,model_type,provider,model_name,base_url,secret_id,enabled,capability_status,
                       capabilities::text,api_key_ciphertext,update_time
                FROM kb_model_config WHERE user_id=? AND revoked_at IS NULL ORDER BY update_time DESC
                """, (rs, n) -> view(rs.getString("id"), rs.getString("model_type"), rs.getString("provider"),
                rs.getString("model_name"), rs.getString("base_url"), rs.getString("secret_id"),
                rs.getBoolean("enabled"), rs.getString("capability_status"), rs.getString("capabilities"),
                rs.getString("api_key_ciphertext"), rs.getTimestamp("update_time").toInstant().toString()), userId);
    }

    @Transactional
    public Config save(String userId, ModelConfigRequest request) {
        String provider = request.provider().trim().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(provider)) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的模型 provider");
        }
        String modelType = request.modelType().trim().toUpperCase();
        if (!"CHAT".equals(modelType)) {
            throw new BusinessException(ErrorCode.VALIDATION,
                    "当前 BYOK 配置仅支持 CHAT；Embedding 固定使用 Qwen3，reranker 由平台独立配置");
        }
        String modelName = request.modelName().trim();
        String baseUrl = normalizeBaseUrl(provider, request.baseUrl());
        String encrypted = request.apiKey() == null || request.apiKey().isBlank() ? null : secrets.protect(request.apiKey().trim());
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO kb_model_config(id,user_id,model_type,provider,model_name,base_url,secret_id,
                    api_key_ciphertext,enabled,capability_status,capabilities,update_time)
                VALUES (?,?,?,?,?,?,?, ?,?,'UNPROBED','[]'::jsonb,NOW())
                ON CONFLICT(user_id,model_type,provider,model_name) DO UPDATE SET
                    base_url=EXCLUDED.base_url,secret_id=EXCLUDED.secret_id,
                    api_key_ciphertext=COALESCE(EXCLUDED.api_key_ciphertext,kb_model_config.api_key_ciphertext),
                    enabled=EXCLUDED.enabled,revoked_at=NULL,capability_status='UNPROBED',update_time=NOW()
                """, id, userId, modelType, provider, modelName, baseUrl, request.secretId(), encrypted, request.enabled());
        Config saved = find(userId, modelType, provider, modelName);
        if (request.enabled()) {
            Validation validation = validate(userId, saved.id());
            if (!"READY".equals(validation.status())) {
                throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE,
                        "模型能力探测失败，配置未保存");
            }
            return find(userId, modelType, provider, modelName);
        }
        return saved;
    }

    public Validation validate(String userId, String id) {
        limiter.checkModel(userId);
        Stored stored = stored(userId, id);
        if (!stored.enabled()) return new Validation(id, "DISABLED", "配置已停用", stored.capabilities());
        String key = stored.encryptedKey() == null ? null : secrets.reveal(stored.encryptedKey());
        ChatModel model = null;
        try {
            model = nativeModels.create(stored.provider(), stored.modelName(), checkedBaseUrl(stored), key);
            String sync = text(model.call(new Prompt("Reply with exactly: OK")));
            if (sync.isBlank()) throw new IllegalStateException("同步回答为空");
            StringBuilder streamed = new StringBuilder();
            model.stream(new Prompt("Reply with exactly: OK")).toIterable()
                    .forEach(response -> streamed.append(text(response)));
            List<String> capabilities = new ArrayList<>();
            capabilities.add("CHAT");
            if (!streamed.isEmpty()) capabilities.add("STREAM");
            if (probeJsonSchema(model, stored.provider(), stored.modelName())) capabilities.add("JSON_SCHEMA");
            if (probeToolCalling(model, stored.provider(), stored.modelName())) capabilities.add("TOOL_CALLING");
            if (probeVision(model)) capabilities.add("VISION");
            capabilities.add("CONTEXT_WINDOW:UNVERIFIED");
            capabilities.add("MAX_OUTPUT:1024");
            mark(id, userId, "READY", capabilities);
            return new Validation(id, "READY", "已通过 Spring AI 能力探测；无法统一读取的上下文窗口明确标为未验证",
                    List.copyOf(capabilities));
        } catch (Exception error) {
            mark(id, userId, "FAILED", List.of());
            return new Validation(id, "FAILED", "模型端点不可访问", stored.capabilities());
        } finally {
            close(model);
        }
    }

    @Override
    public String generate(String userId, String prompt) {
        limiter.checkModel(userId);
        Stored stored = chatModel(userId);
        String key = stored.encryptedKey() == null ? null : secrets.reveal(stored.encryptedKey());
        ChatModel model = null;
        try {
            model = nativeModels.create(stored.provider(), stored.modelName(), checkedBaseUrl(stored), key);
            String answer = streamText(model, prompt);
            if (answer.isBlank()) throw new IllegalStateException("模型未返回文本内容");
            return answer;
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("用户模型调用失败", error);
        } finally {
            close(model);
        }
    }

    @Override
    public ModelClient create(String userId, String configId) {
        Stored stored = stored(userId, configId);
        if (!stored.enabled() || !"READY".equals(stored.capabilityStatus())
                || !"CHAT".equalsIgnoreCase(stored.modelType())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "模型配置尚未通过 CHAT 能力验证");
        }
        String key = stored.encryptedKey() == null ? null : secrets.reveal(stored.encryptedKey());
        java.util.Set<Capability> capabilities = stored.capabilities().stream()
                .flatMap(value -> {
                    try { return java.util.stream.Stream.of(Capability.valueOf(value)); }
                    catch (IllegalArgumentException ignored) { return java.util.stream.Stream.empty(); }
                }).collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new ModelClient() {
            @Override public String generate(String prompt) {
                limiter.checkModel(userId);
                ChatModel model = nativeModels.create(stored.provider(), stored.modelName(), checkedBaseUrl(stored), key);
                try {
                    String answer = streamText(model, prompt);
                    if (answer.isBlank()) throw new IllegalStateException("模型未返回文本内容");
                    return answer;
                } finally { close(model); }
            }
            @Override public java.util.Set<Capability> capabilities() { return capabilities; }
        };
    }

    @Override
    public void stream(String userId, String prompt, Consumer<String> consumer) {
        limiter.checkModel(userId);
        Stored stored = chatModel(userId);
        String key = stored.encryptedKey() == null ? null : secrets.reveal(stored.encryptedKey());
        ChatModel model = null;
        try {
            model = nativeModels.create(stored.provider(), stored.modelName(), checkedBaseUrl(stored), key);
            boolean[] emitted = {false};
            modelInvoker.stream(model, prompt, chunk -> {
                emitted[0] = true;
                consumer.accept(chunk);
            });
            if (!emitted[0]) throw new IllegalStateException("用户模型未返回流式内容");
        } catch (BusinessException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("用户模型流式调用失败", error);
        } finally {
            close(model);
        }
    }

    private Stored chatModel(String userId) {
        Stored stored = jdbc.query("""
                SELECT id,user_id,model_type,provider,model_name,base_url,secret_id,api_key_ciphertext,
                       enabled,capability_status,capabilities::text
                FROM kb_model_config WHERE user_id=? AND model_type='CHAT' AND enabled=TRUE AND revoked_at IS NULL
                ORDER BY update_time DESC LIMIT 1
                """, (rs, n) -> new Stored(rs.getString("id"), rs.getString("user_id"), rs.getString("model_type"),
                rs.getString("provider"), rs.getString("model_name"), rs.getString("base_url"), rs.getString("secret_id"),
                rs.getString("api_key_ciphertext"), rs.getBoolean("enabled"), rs.getString("capability_status"), parse(rs.getString("capabilities"))), userId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.FORBIDDEN, "请先配置并启用自己的模型"));
        if (!"READY".equals(stored.capabilityStatus())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "请先完成模型连通性验证后再调用");
        }
        return stored;
    }

    @Override
    public boolean configured(String userId) {
        return jdbc.query("SELECT 1 FROM kb_model_config WHERE user_id=? AND model_type='CHAT' AND enabled=TRUE AND revoked_at IS NULL AND capability_status='READY' LIMIT 1",
                (rs, n) -> rs.getInt(1), userId).stream().findFirst().isPresent();
    }

    @Override
    public String selectedModel(String userId) {
        return jdbc.query("SELECT model_name FROM kb_model_config WHERE user_id=? AND model_type='CHAT' AND enabled=TRUE AND revoked_at IS NULL ORDER BY update_time DESC LIMIT 1",
                (rs, n) -> rs.getString(1), userId).stream().findFirst().orElse("user-configured");
    }

    public void revoke(String userId, String id) {
        if (jdbc.update("UPDATE kb_model_config SET revoked_at=NOW(),enabled=FALSE,update_time=NOW() WHERE id=? AND user_id=? AND revoked_at IS NULL", id, userId) == 0) {
            throw new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在");
        }
    }

    private Config find(String userId, String type, String provider, String name) {
        return jdbc.query("""
                SELECT id,model_type,provider,model_name,base_url,secret_id,enabled,capability_status,
                       capabilities::text,api_key_ciphertext,update_time
                FROM kb_model_config WHERE user_id=? AND model_type=? AND provider=? AND model_name=? AND revoked_at IS NULL
                """, (rs, n) -> view(rs.getString("id"), rs.getString("model_type"), rs.getString("provider"),
                rs.getString("model_name"), rs.getString("base_url"), rs.getString("secret_id"),
                rs.getBoolean("enabled"), rs.getString("capability_status"), rs.getString("capabilities"),
                rs.getString("api_key_ciphertext"), rs.getTimestamp("update_time").toInstant().toString()),
                userId, type, provider, name).stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.INTERNAL, "模型配置保存失败"));
    }

    private Stored stored(String userId, String id) {
        return jdbc.query("""
                SELECT id,user_id,model_type,provider,model_name,base_url,secret_id,api_key_ciphertext,
                       enabled,capability_status,capabilities::text
                FROM kb_model_config WHERE id=? AND user_id=? AND revoked_at IS NULL
                """, (rs, n) -> new Stored(rs.getString("id"), rs.getString("user_id"), rs.getString("model_type"),
                rs.getString("provider"), rs.getString("model_name"), rs.getString("base_url"), rs.getString("secret_id"),
                rs.getString("api_key_ciphertext"), rs.getBoolean("enabled"), rs.getString("capability_status"), parse(rs.getString("capabilities"))), id, userId)
                .stream().findFirst().orElseThrow(() -> new BusinessException(ErrorCode.NOT_FOUND, "模型配置不存在"));
    }

    private void mark(String id, String userId, String status, List<String> capabilities) {
        try {
            jdbc.update("UPDATE kb_model_config SET capability_status=?,capabilities=CAST(? AS jsonb),update_time=NOW() WHERE id=? AND user_id=?",
                    status, json.writeValueAsString(capabilities == null ? List.of() : capabilities), id, userId);
        } catch (com.fasterxml.jackson.core.JsonProcessingException error) {
            throw new IllegalStateException("模型能力矩阵无法序列化", error);
        }
    }

    private String text(org.springframework.ai.chat.model.ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                ? "" : response.getResult().getOutput().getText();
    }

    private boolean probeJsonSchema(ChatModel model, String provider, String modelName) {
        try {
            String schema = """
                    {"type":"object","properties":{"status":{"type":"string","const":"OK"}},
                     "required":["status"],"additionalProperties":false}
                    """;
            var options = nativeModels.structuredOptions(provider, modelName, schema);
            String value = text(model.call(new Prompt("Return a JSON object whose status is exactly OK.", options)));
            return "OK".equals(json.readTree(value).path("status").asText());
        } catch (Exception unsupported) {
            return false;
        }
    }

    private boolean probeToolCalling(ChatModel model, String provider, String modelName) {
        try {
            var callback = org.springframework.ai.tool.function.FunctionToolCallback
                    .builder("modelrag_capability_probe", () -> "OK")
                    .description("Return OK for a model capability probe").build();
            var options = nativeModels.toolOptions(provider, modelName, callback);
            var response = model.call(new Prompt(
                    "Call the modelrag_capability_probe tool now. Do not answer without calling it.", options));
            return response != null && response.hasToolCalls();
        } catch (Exception unsupported) {
            return false;
        }
    }

    private boolean probeVision(ChatModel model) {
        try {
            byte[] pixel = java.util.Base64.getDecoder().decode(
                    "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAusB9Y9Z5nEAAAAASUVORK5CYII=");
            var media = org.springframework.ai.content.Media.builder()
                    .mimeType(org.springframework.util.MimeTypeUtils.IMAGE_PNG)
                    .data(new org.springframework.core.io.ByteArrayResource(pixel)).name("probe.png").build();
            var message = org.springframework.ai.chat.messages.UserMessage.builder()
                    .text("Reply with one word describing whether an image is attached.").media(media).build();
            return !text(model.call(new Prompt(message))).isBlank();
        } catch (Exception unsupported) {
            return false;
        }
    }

    private String streamText(ChatModel model, String prompt) {
        return modelInvoker.invoke(model, prompt);
    }

    private void close(ChatModel model) {
        if (model instanceof AutoCloseable closeable) {
            try { closeable.close(); }
            catch (Exception ignored) { }
        }
    }

    private Config view(String id, String type, String provider, String name, String baseUrl, String secretId,
            boolean enabled, String status, String capabilities, String encryptedKey, String updateTime) {
        String hint = encryptedKey == null ? null : secrets.mask(secrets.reveal(encryptedKey));
        return new Config(id, type, provider, name, baseUrl, secretId, enabled, status, parse(capabilities),
                encryptedKey != null && !encryptedKey.isBlank(), hint, updateTime);
    }

    private List<String> parse(String value) {
        try { return value == null ? List.of() : json.readValue(value, new TypeReference<List<String>>() {}); }
        catch (Exception ignored) { return List.of(); }
    }

    private String normalizeBaseUrl(String provider, String value) {
        String normalizedProvider = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        if (!PROVIDERS.contains(normalizedProvider)) {
            throw new BusinessException(ErrorCode.VALIDATION, "不支持的模型 provider");
        }
        String defaultUrl = DEFAULT_URLS.get(normalizedProvider);
        if ((value == null || value.isBlank()) && defaultUrl == null) {
            throw new BusinessException(ErrorCode.VALIDATION, "OpenAI-compatible provider 必须显式配置 baseUrl");
        }
        String url = value == null || value.isBlank() ? defaultUrl : value.trim();
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException error) { throw new BusinessException(ErrorCode.VALIDATION, "模型 baseUrl 不合法"); }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase();
        String host = uri.getHost() == null ? "" : uri.getHost().toLowerCase();
        if (host.isBlank() || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null
                || uri.getPort() == 0) {
            throw new BusinessException(ErrorCode.VALIDATION, "模型 baseUrl 不合法");
        }
        boolean localOllama = "ollama".equals(normalizedProvider) && isLoopbackHost(host);
        if (!("https".equals(scheme) || localOllama)) throw new BusinessException(ErrorCode.VALIDATION, "云模型只允许 HTTPS；本地 Ollama 仅允许回环地址");
        if (!localOllama && !OFFICIAL_HOSTS.contains(host) && !allowedHosts.contains(host)) {
            throw new BusinessException(ErrorCode.VALIDATION, "模型域名不在管理员 allowlist");
        }
        validateAddresses(host, localOllama);
        return url.replaceAll("/$", "");
    }

    private String checkedBaseUrl(Stored stored) {
        String checked = normalizeBaseUrl(stored.provider(), stored.baseUrl());
        if (!checked.equals(stored.baseUrl())) {
            throw new BusinessException(ErrorCode.VALIDATION, "模型 baseUrl 规范化结果不一致");
        }
        return checked;
    }

    private void validateAddresses(String host, boolean localOllama) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) throw new BusinessException(ErrorCode.VALIDATION, "模型域名无法解析");
            for (InetAddress address : addresses) {
                boolean restricted = address.isAnyLocalAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isLoopbackAddress() || address.isMulticastAddress()
                        || ipv6UniqueLocal(address) || carrierGradeNat(address);
                if (restricted && !(localOllama && address.isLoopbackAddress())) {
                    throw new BusinessException(ErrorCode.VALIDATION, "模型域名解析到受限地址");
                }
            }
        } catch (UnknownHostException error) {
            throw new BusinessException(ErrorCode.VALIDATION, "模型域名无法解析");
        }
    }

    private boolean isLoopbackHost(String host) {
        return "127.0.0.1".equals(host) || "localhost".equals(host) || "::1".equals(host);
    }

    private boolean ipv6UniqueLocal(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xfe) == 0xfc;
    }

    private boolean carrierGradeNat(InetAddress address) {
        byte[] bytes = address.getAddress();
        return bytes.length == 4 && (bytes[0] & 0xff) == 100 && (bytes[1] & 0xc0) == 64;
    }

}
