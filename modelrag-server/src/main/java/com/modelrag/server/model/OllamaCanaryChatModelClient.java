package com.modelrag.server.model;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.ollama.management.ModelManagementOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import java.util.function.Consumer;

@Component
@Order(-90)
@ConditionalOnExpression("'${modelrag.ollama.enabled:false}' == 'true' && '${modelrag.ollama.canary-model:}' != ''")
public class OllamaCanaryChatModelClient implements ModelClient {
    private final String model;
    private final ChatClient client;

    public OllamaCanaryChatModelClient(@Value("${modelrag.ollama.url:http://127.0.0.1:11434}") String url,
            @Value("${modelrag.ollama.canary-model}") String model) {
        this.model = model;
        OllamaChatModel chatModel = OllamaChatModel.builder()
                .ollamaApi(OllamaApi.builder().baseUrl(normalizeUrl(url)).build())
                .options(OllamaChatOptions.builder().model(model).numCtx(2048).temperature(0d).disableThinking().build())
                .modelManagementOptions(ModelManagementOptions.defaults())
                .build();
        this.client = ChatClient.create(chatModel);
    }

    @Override public String name() { return "ollama-canary-" + model; }
    @Override public ModelType type() { return ModelType.CHAT; }
    @Override public String execute(String input) {
        String answer = client.prompt().user(input == null ? "" : input).call().content();
        if (answer == null || answer.isBlank()) throw new IllegalStateException("Ollama Canary 未返回回答");
        return answer;
    }
    @Override public void stream(String input, Consumer<String> consumer) {
        boolean[] emitted = {false};
        client.prompt().user(input == null ? "" : input).stream().content().toIterable().forEach(chunk -> {
            if (chunk != null && !chunk.isEmpty()) { emitted[0] = true; consumer.accept(chunk); }
        });
        if (!emitted[0]) throw new IllegalStateException("Ollama Canary 未返回流式回答");
    }

    private String normalizeUrl(String value) {
        String text = value == null || value.isBlank() ? "http://127.0.0.1:11434" : value.trim();
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://" + text;
        return text.replaceAll("/$", "");
    }
}
