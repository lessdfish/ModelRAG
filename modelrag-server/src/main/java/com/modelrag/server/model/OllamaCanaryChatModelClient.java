package com.modelrag.server.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

@Component
@Order(-90)
@ConditionalOnExpression("'${modelrag.ollama.enabled:false}' == 'true' && '${modelrag.ollama.canary-model:}' != ''")
public class OllamaCanaryChatModelClient implements ModelClient {
    private final String url;
    private final String model;
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    public OllamaCanaryChatModelClient(@Value("${modelrag.ollama.url:http://127.0.0.1:11434}") String url,
            @Value("${modelrag.ollama.canary-model}") String model, ObjectMapper json) {
        this.url = normalizeUrl(url);
        this.model = model;
        this.json = json;
    }

    public String name() {
        return "ollama-canary-" + model;
    }

    public ModelType type() {
        return ModelType.CHAT;
    }

    public String execute(String input) {
        try {
            String body = json.writeValueAsString(Map.of("model", model, "prompt", input, "stream", false,
                    "options", Map.of("num_ctx", 2048, "temperature", 0)));
            HttpRequest request = HttpRequest.newBuilder(URI.create(url + "/api/generate")).timeout(Duration.ofSeconds(90))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) throw new IllegalStateException("Ollama 返回 HTTP " + response.statusCode());
            JsonNode answer = json.readTree(response.body()).path("response");
            if (answer.asText().isBlank()) throw new IllegalStateException("Ollama 未返回回答");
            return answer.asText();
        } catch (Exception error) {
            throw new IllegalStateException("Ollama Canary Chat 调用失败", error);
        }
    }

    private String normalizeUrl(String value) {
        String text = value == null || value.isBlank() ? "127.0.0.1:11434" : value.trim();
        if (!text.startsWith("http://") && !text.startsWith("https://")) text = "http://" + text;
        return text.replaceAll("/$", "");
    }
}
