package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.modelrag.server.model.SpringAiByokChatModels;
import com.modelrag.server.model.CancellableModelInvoker;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;

/** Protocol-level provider checks against deterministic local HTTP stubs. */
class SpringAiProviderContractTest {
    private static HttpServer server;
    private static java.util.concurrent.ExecutorService serverExecutor;
    private static String baseUrl;
    private static final AtomicReference<CountDownLatch> cancelEntered = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> cancelRelease = new AtomicReference<>();
    private static final AtomicReference<CountDownLatch> cancelFinished = new AtomicReference<>();
    private static final AtomicBoolean cancelSocketClosed = new AtomicBoolean();

    @BeforeAll
    static void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", SpringAiProviderContractTest::respond);
        serverExecutor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(serverExecutor);
        server.start();
        baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @AfterAll
    static void stopServer() {
        if (server != null) server.stop(0);
        if (serverExecutor != null) serverExecutor.close();
    }

    static Stream<Arguments> providers() {
        return Stream.of(
                Arguments.of("openai", "gpt-4o-mini", "key"),
                Arguments.of("openai-compatible", "qwen-plus", "key"),
                Arguments.of("anthropic", "claude-sonnet-4-20250514", "key"),
                Arguments.of("gemini", "gemini-2.5-flash", "key"),
                Arguments.of("deepseek", "deepseek-chat", "key"),
                Arguments.of("ollama", "qwen3", null));
    }

    @ParameterizedTest(name = "{0} synchronous and streaming protocol")
    @MethodSource("providers")
    void nativeProviderSupportsSyncAndStream(String provider, String modelName, String key) {
        ChatModel model = new SpringAiByokChatModels().create(provider, modelName, baseUrl, key);
        assertEquals("OK", text(model.call(new Prompt("contract-sync"))));
        String streamed = model.stream(new Prompt("contract-stream")).map(SpringAiProviderContractTest::text)
                .filter(value -> !value.isBlank()).collectList().block().stream().reduce("", String::concat);
        assertEquals("OK", streamed);
    }

    @org.junit.jupiter.api.Timeout(10)
    @ParameterizedTest(name = "{0} HTTP error mapping")
    @MethodSource("providers")
    void nativeProviderMapsHttpErrors(String provider, String modelName, String key) {
        ChatModel model = new SpringAiByokChatModels().create(provider, modelName, baseUrl, key);
        assertThrows(RuntimeException.class, () -> model.call(new Prompt("contract-force-error")));
    }

    @ParameterizedTest(name = "{0} structured JSON and tool protocol")
    @MethodSource("providers")
    void nativeProviderSupportsStructuredJsonAndToolCalls(String provider, String modelName, String key) throws Exception {
        ChatModel model = new SpringAiByokChatModels().create(provider, modelName, baseUrl, key);
        String schema = """
                {"type":"object","properties":{"status":{"type":"string"}},"required":["status"]}
                """;
        SpringAiByokChatModels factory = new SpringAiByokChatModels();
        var structured = factory.structuredOptions(provider, modelName, schema);
        String json = text(model.call(new Prompt("contract-json-schema", structured)));
        assertEquals("OK", new com.fasterxml.jackson.databind.ObjectMapper().readTree(json).path("status").asText());

        var callback = org.springframework.ai.tool.function.FunctionToolCallback
                .builder("modelrag_capability_probe", () -> "OK")
                .description("Return OK for the provider contract").build();
        var toolOptions = factory.toolOptions(provider, modelName, callback);
        assertTrue(model.call(new Prompt("contract-tool-call modelrag_capability_probe", toolOptions)).hasToolCalls());
    }

    @org.junit.jupiter.api.Timeout(10)
    @ParameterizedTest(name = "{0} cancels an in-flight provider invocation")
    @MethodSource("providers")
    void nativeProviderCancellationClosesConnection(String provider, String modelName, String key) throws Exception {
        cancelEntered.set(new CountDownLatch(1));
        cancelRelease.set(new CountDownLatch(1));
        cancelFinished.set(new CountDownLatch(1));
        cancelSocketClosed.set(false);
        ChatModel model = new SpringAiByokChatModels().create(provider, modelName, baseUrl, key);
        CancellableModelInvoker invoker = new CancellableModelInvoker();
        AtomicReference<Thread> owner = new AtomicReference<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var future = executor.submit(() -> {
                owner.set(Thread.currentThread());
                return invoker.invoke(model, "contract-cancel");
            });
            assertTrue(cancelEntered.get().await(3, TimeUnit.SECONDS), "stub did not receive cancellation request");
            assertTrue(invoker.cancel(owner.get()));
            assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> future.get(1, TimeUnit.SECONDS), "business invocation did not finish promptly after cancellation");
            cancelRelease.get().countDown();
            assertTrue(cancelFinished.get().await(3, TimeUnit.SECONDS), "stub request did not finish after cancellation");
        }
    }

    @org.junit.jupiter.api.Test
    void unknownProviderAndMissingCloudKeyFailClosed() {
        SpringAiByokChatModels factory = new SpringAiByokChatModels();
        assertThrows(IllegalArgumentException.class, () -> factory.create("typo", "model", baseUrl, "key"));
        assertThrows(IllegalArgumentException.class, () -> factory.create("openai", "model", baseUrl, " "));
    }

    private static String text(org.springframework.ai.chat.model.ChatResponse response) {
        return response == null || response.getResult() == null || response.getResult().getOutput() == null
                || response.getResult().getOutput().getText() == null ? "" : response.getResult().getOutput().getText();
    }

    private static void respond(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        if (request.contains("contract-force-error")) {
            send(exchange, 503, "application/json", "{\"error\":{\"message\":\"stub unavailable\"}}");
            return;
        }
        if (request.contains("contract-cancel")) {
            cancellationResponse(exchange, path);
            return;
        }
        if (request.contains("contract-tool-call")) {
            if (path.contains("/api/chat")) send(exchange, 200, "application/json", ollamaTool());
            else if (path.contains("/messages")) send(exchange, 200, "application/json", anthropicTool());
            else if (path.toLowerCase(java.util.Locale.ROOT).contains("generatecontent")) {
                send(exchange, 200, "application/json", geminiTool());
            } else send(exchange, 200, "application/json", openAiTool());
            return;
        }
        if (request.contains("contract-json-schema")) {
            if (path.contains("/api/chat")) send(exchange, 200, "application/json", ollama("{\"status\":\"OK\"}", true));
            else if (path.contains("/messages")) send(exchange, 200, "application/json", anthropic("{\"status\":\"OK\"}"));
            else if (path.toLowerCase(java.util.Locale.ROOT).contains("generatecontent")) {
                send(exchange, 200, "application/json", gemini("{\"status\":\"OK\"}"));
            } else send(exchange, 200, "application/json", openAi("{\"status\":\"OK\"}"));
            return;
        }
        boolean stream = request.contains("\"stream\":true") || path.contains("streamGenerateContent")
                || exchange.getRequestURI().getQuery() != null && exchange.getRequestURI().getQuery().contains("alt=sse");
        if (path.contains("/api/chat")) {
            send(exchange, 200, stream ? "application/x-ndjson" : "application/json", stream
                    ? ollama("O", false) + "\n" + ollama("K", true) + "\n"
                    : ollama("OK", true));
        } else if (path.contains("/messages")) {
            send(exchange, 200, stream ? "text/event-stream" : "application/json", stream
                    ? anthropicStream() : anthropic("OK"));
        } else if (path.toLowerCase(java.util.Locale.ROOT).contains("generatecontent")) {
            send(exchange, 200, stream ? "text/event-stream" : "application/json", stream
                    ? "data: " + gemini("O") + "\n\ndata: " + gemini("K") + "\n\n"
                    : gemini("OK"));
        } else {
            send(exchange, 200, stream ? "text/event-stream" : "application/json", stream
                    ? openAiStream() : openAi("OK"));
        }
    }

    private static String openAi(String text) {
        return "{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"stub\","+
                "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped(text) +
                "\"},\"finish_reason\":\"stop\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    private static String openAiStream() {
        return "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"O\"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"chatcmpl-test\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"K\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: [DONE]\n\n";
    }

    private static String openAiTool() {
        return "{\"id\":\"chatcmpl-tool\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"stub\","+
                "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{"+
                "\"id\":\"call_1\",\"type\":\"function\",\"function\":{\"name\":\"modelrag_capability_probe\",\"arguments\":\"{}\"}}]},"+
                "\"finish_reason\":\"tool_calls\"}],\"usage\":{\"prompt_tokens\":1,\"completion_tokens\":1,\"total_tokens\":2}}";
    }

    private static String anthropic(String text) {
        return "{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"stub\","+
                "\"content\":[{\"type\":\"text\",\"text\":\"" + escaped(text) + "\"}],\"stop_reason\":\"end_turn\","+
                "\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    }

    private static String anthropicStream() {
        return "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_test\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"stub\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n"
                + "event: content_block_start\ndata: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n"
                + "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"OK\"}}\n\n"
                + "event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n"
                + "event: message_delta\ndata: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\",\"stop_sequence\":null},\"usage\":{\"output_tokens\":1}}\n\n"
                + "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
    }

    private static String anthropicTool() {
        return "{\"id\":\"msg_tool\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"stub\","+
                "\"content\":[{\"type\":\"tool_use\",\"id\":\"tool_1\",\"name\":\"modelrag_capability_probe\",\"input\":{}}],"+
                "\"stop_reason\":\"tool_use\",\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}";
    }

    private static String ollama(String text, boolean done) {
        return "{\"model\":\"qwen3\",\"created_at\":\"2026-01-01T00:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"" + escaped(text) +
                "\"},\"done\":" + done + ",\"done_reason\":\"stop\",\"total_duration\":1,\"load_duration\":1,\"prompt_eval_count\":1,\"eval_count\":1}";
    }

    private static String ollamaTool() {
        return "{\"model\":\"qwen3\",\"created_at\":\"2026-01-01T00:00:00Z\",\"message\":{\"role\":\"assistant\",\"content\":\"\","+
                "\"tool_calls\":[{\"function\":{\"name\":\"modelrag_capability_probe\",\"arguments\":{}}}]},\"done\":true,\"done_reason\":\"stop\"}";
    }

    private static String gemini(String text) {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"" + escaped(text) +
                "\"}]},\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1,\"totalTokenCount\":2},\"modelVersion\":\"stub\"}";
    }

    private static String geminiTool() {
        return "{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"functionCall\":{\"name\":\"modelrag_capability_probe\",\"args\":{}}}]},"+
                "\"finishReason\":\"STOP\",\"index\":0}],\"usageMetadata\":{\"promptTokenCount\":1,\"candidatesTokenCount\":1,\"totalTokenCount\":2},\"modelVersion\":\"stub\"}";
    }

    private static void cancellationResponse(HttpExchange exchange, String path) throws IOException {
        String first;
        String next;
        String type;
        if (path.contains("/api/chat")) {
            type = "application/x-ndjson";
            first = ollama("O", false) + "\n";
            next = ollama("K", false) + "\n";
        } else if (path.contains("/messages")) {
            type = "text/event-stream";
            first = "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_cancel\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"stub\",\"content\":[],\"stop_reason\":null,\"stop_sequence\":null,\"usage\":{\"input_tokens\":1,\"output_tokens\":0}}}\n\n";
            next = "event: content_block_delta\ndata: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"K\"}}\n\n";
        } else if (path.toLowerCase(java.util.Locale.ROOT).contains("generatecontent")) {
            type = "text/event-stream";
            first = "data: " + gemini("O") + "\n\n";
            next = "data: " + gemini("K") + "\n\n";
        } else {
            type = "text/event-stream";
            first = "data: {\"id\":\"chatcmpl-cancel\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\",\"content\":\"O\"},\"finish_reason\":null}]}\n\n";
            next = "data: {\"id\":\"chatcmpl-cancel\",\"object\":\"chat.completion.chunk\",\"created\":1,\"model\":\"stub\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"K\"},\"finish_reason\":null}]}\n\n";
        }
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(200, 0);
        try {
            exchange.getResponseBody().write(first.getBytes(StandardCharsets.UTF_8));
            exchange.getResponseBody().flush();
            cancelEntered.get().countDown();
            cancelRelease.get().await(3, TimeUnit.SECONDS);
            try {
                exchange.getResponseBody().write(next.getBytes(StandardCharsets.UTF_8));
                exchange.getResponseBody().flush();
            } catch (IOException closed) {
                cancelSocketClosed.set(true);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } finally {
            exchange.close();
            cancelFinished.get().countDown();
        }
    }

    private static void send(HttpExchange exchange, int status, String type, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", type);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static String escaped(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
