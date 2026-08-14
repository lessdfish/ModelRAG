package com.modelrag.server.model;

import com.anthropic.models.messages.Model;
import com.google.genai.Client;
import com.google.genai.types.HttpOptions;
import com.google.genai.types.HttpRetryOptions;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.ai.anthropic.AnthropicChatModel;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.deepseek.DeepSeekChatModel;
import org.springframework.ai.deepseek.DeepSeekChatOptions;
import org.springframework.ai.deepseek.api.DeepSeekApi;
import org.springframework.ai.google.genai.GoogleGenAiChatModel;
import org.springframework.ai.google.genai.GoogleGenAiChatOptions;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.core.retry.RetryPolicy;
import org.springframework.core.retry.RetryTemplate;

/** Creates request-scoped Spring AI 2.0 adapters from user-owned credentials. */
@Component
public class SpringAiByokChatModels {

    public ChatModel create(String provider, String modelName, String baseUrl, String apiKey) {
        String type = provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
        return switch (type) {
            case "anthropic" -> anthropic(modelName, baseUrl, apiKey);
            case "gemini", "google" -> google(modelName, baseUrl, apiKey);
            case "deepseek" -> deepSeek(modelName, baseUrl, apiKey);
            case "ollama" -> ollama(modelName, baseUrl);
            case "openai", "openai-compatible", "qwen", "zhipu", "kimi", "doubao", "minimax", "groq", "vllm" ->
                    openAi(modelName, baseUrl, apiKey);
            default -> throw new IllegalArgumentException("不支持的模型 provider: " + provider);
        };
    }

    /** Runtime options must match the native adapter type; generic options are rejected by Spring AI 2.0. */
    public ChatOptions structuredOptions(String provider, String modelName, String schema) {
        String type = normalized(provider);
        return switch (type) {
            case "anthropic" -> AnthropicChatOptions.builder().model(Model.of(modelName))
                    .maxTokens(64).outputSchema(schema).build();
            case "gemini", "google" -> GoogleGenAiChatOptions.builder().model(googleModel(modelName))
                    .maxOutputTokens(64).responseMimeType("application/json").outputSchema(schema).build();
            case "deepseek" -> DeepSeekChatOptions.builder().model(deepSeekModel(modelName)).maxTokens(64)
                    .responseFormat(org.springframework.ai.deepseek.api.ResponseFormat.builder()
                            .type(org.springframework.ai.deepseek.api.ResponseFormat.Type.JSON_OBJECT).build())
                    .build();
            case "ollama" -> OllamaChatOptions.builder().model(modelName).numPredict(64).outputSchema(schema).build();
            case "openai", "openai-compatible", "qwen", "zhipu", "kimi", "doubao", "minimax", "groq", "vllm" ->
                    OpenAiChatOptions.builder().model(modelName).maxTokens(64).outputSchema(schema).build();
            default -> throw new IllegalArgumentException("不支持的模型 provider: " + provider);
        };
    }

    public ChatOptions toolOptions(String provider, String modelName, ToolCallback callback) {
        String type = normalized(provider);
        return switch (type) {
            case "anthropic" -> AnthropicChatOptions.builder().model(Model.of(modelName))
                    .maxTokens(64).toolCallbacks(callback).build();
            case "gemini", "google" -> GoogleGenAiChatOptions.builder().model(googleModel(modelName))
                    .maxOutputTokens(64).toolCallbacks(callback).build();
            case "deepseek" -> DeepSeekChatOptions.builder().model(deepSeekModel(modelName)).maxTokens(64)
                    .toolCallbacks(callback).build();
            case "ollama" -> OllamaChatOptions.builder().model(modelName).numPredict(64)
                    .toolCallbacks(callback).build();
            case "openai", "openai-compatible", "qwen", "zhipu", "kimi", "doubao", "minimax", "groq", "vllm" ->
                    OpenAiChatOptions.builder().model(modelName).maxTokens(64).toolCallbacks(callback).build();
            default -> throw new IllegalArgumentException("不支持的模型 provider: " + provider);
        };
    }

    private ChatModel google(String modelName, String baseUrl, String apiKey) {
        var model = googleModel(modelName);
        Client client = Client.builder().apiKey(requiredKey(apiKey))
                .httpOptions(HttpOptions.builder().baseUrl(baseUrl).timeout(45_000)
                        .retryOptions(HttpRetryOptions.builder().attempts(1).build()).build()).build();
        GoogleGenAiChatModel chat = GoogleGenAiChatModel.builder().genAiClient(client)
                .options(GoogleGenAiChatOptions.builder().model(model).maxTokens(1024).build())
                .retryTemplate(noRetry())
                .build();
        return new ManagedChatModel(chat, client::close);
    }

    private ChatModel openAi(String modelName, String baseUrl, String apiKey) {
        var transport = org.springframework.ai.openai.http.okhttp.SpringAiOpenAiHttpClient.builder()
                .timeout(java.time.Duration.ofSeconds(30)).build();
        var options = com.openai.core.ClientOptions.builder().httpClient(transport)
                .baseUrl(baseUrl).apiKey(requiredKey(apiKey))
                .maxRetries(0).timeout(java.time.Duration.ofSeconds(30)).build();
        var client = new com.openai.client.OpenAIClientImpl(options);
        OpenAiChatModel chat = OpenAiChatModel.builder().openAiClient(client).openAiClientAsync(client.async())
                .options(OpenAiChatOptions.builder().model(modelName).maxTokens(1024).build()).build();
        return new ManagedChatModel(chat, client::close);
    }

    private ChatModel anthropic(String modelName, String baseUrl, String apiKey) {
        AnthropicChatModel chat = AnthropicChatModel.builder()
                .options(AnthropicChatOptions.builder().baseUrl(baseUrl).apiKey(requiredKey(apiKey))
                        .model(Model.of(modelName)).maxTokens(1024).maxRetries(0)
                        .timeout(java.time.Duration.ofSeconds(30)).build())
                .httpClientBuilderCustomizer(builder -> builder.timeout(java.time.Duration.ofSeconds(30)))
                .build();
        return new ManagedChatModel(chat, chat.getAnthropicClient()::close);
    }

    private ChatModel deepSeek(String modelName, String baseUrl, String apiKey) {
        DeepSeekChatModel chat = DeepSeekChatModel.builder()
                .deepSeekApi(DeepSeekApi.builder().baseUrl(baseUrl).apiKey(requiredKey(apiKey)).build())
                .options(DeepSeekChatOptions.builder().model(deepSeekModel(modelName)).maxTokens(1024).build())
                .retryTemplate(noRetry()).build();
        return new ManagedChatModel(chat, () -> { });
    }

    private ChatModel ollama(String modelName, String baseUrl) {
        OllamaChatModel chat = OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(baseUrl).build())
                .options(OllamaChatOptions.builder().model(modelName).temperature(0d).build())
                .modelManagementOptions(ModelManagementOptions.defaults()).retryTemplate(noRetry()).build();
        return new ManagedChatModel(chat, () -> { });
    }

    private DeepSeekApi.ChatModel deepSeekModel(String modelName) {
        return Arrays.stream(DeepSeekApi.ChatModel.values())
                .filter(value -> value.getValue().equalsIgnoreCase(modelName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("DeepSeek 原生适配器不支持该模型名: " + modelName));
    }

    private GoogleGenAiChatModel.ChatModel googleModel(String modelName) {
        return Arrays.stream(GoogleGenAiChatModel.ChatModel.values())
                .filter(value -> value.getValue().equalsIgnoreCase(modelName)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Google GenAI 原生适配器不支持该模型名: " + modelName));
    }

    private String normalized(String provider) {
        return provider == null ? "" : provider.trim().toLowerCase(Locale.ROOT);
    }

    private String requiredKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) throw new IllegalArgumentException("云模型 API Key 不能为空");
        return apiKey;
    }

    private RetryTemplate noRetry() {
        return new RetryTemplate(RetryPolicy.withMaxRetries(0));
    }
}
