package com.modelrag.agent.tool;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class HttpToolInvoker {
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json;
    private final long timeoutMillis;

    public HttpToolInvoker(ObjectMapper json, @Value("${modelrag.tools.http-timeout-ms:30000}") long timeoutMillis) {
        this.json = json;
        this.timeoutMillis = timeoutMillis;
    }

    public String invoke(ToolDefinition tool, String input) {
        if (!tool.http()) throw new IllegalArgumentException("不是 HTTP 工具: " + tool.name());
        try {
            String body = json.writeValueAsString(Map.of("tool", tool.name(), "input", input));
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(tool.endpoint()))
                    .timeout(Duration.ofMillis(timeoutMillis))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body));
            if (tool.authHeaderName() != null && tool.authHeaderValue() != null) {
                builder.header(tool.authHeaderName(), tool.authHeaderValue());
            }
            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) {
                throw new IllegalStateException("HTTP 工具返回状态码 " + response.statusCode());
            }
            return response.body();
        } catch (RuntimeException error) {
            throw error;
        } catch (Exception error) {
            throw new IllegalStateException("HTTP 工具调用失败", error);
        }
    }
}
