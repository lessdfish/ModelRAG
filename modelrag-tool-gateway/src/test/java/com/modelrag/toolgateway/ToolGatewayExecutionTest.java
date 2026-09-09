package com.modelrag.toolgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.catalog.ToolExecutionResolver;
import com.modelrag.toolgateway.catalog.ToolExecutionSpec;
import com.modelrag.toolgateway.coordination.ToolCoordinationStore;
import com.modelrag.toolgateway.execution.InternalToolHandler;
import com.modelrag.toolgateway.execution.InternalToolHandlerRegistry;
import com.modelrag.toolgateway.execution.ResilientToolExecutor;
import com.modelrag.toolgateway.execution.ToolDispatcher;
import com.modelrag.toolgateway.execution.ToolGateway;
import com.modelrag.toolgateway.execution.ToolInvocation;
import com.modelrag.toolgateway.http.HttpToolInvoker;
import com.modelrag.toolgateway.security.ToolCallValidator;
import com.modelrag.toolgateway.trace.ToolCallTracer;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ToolGatewayExecutionTest {
    @Test
    void gatewayDispatchesExplicitInternalHandlerAndReturnsSafeResult() {
        ToolDescriptor descriptor = descriptor("knowledge_lookup", "LOW", "INTERNAL", false, true);
        ToolInvocation invocation = invocation("knowledge_lookup", "query");
        ToolGateway gateway = gateway(descriptor, new InternalToolHandler() {
            @Override public boolean supports(String toolName) { return "knowledge_lookup".equals(toolName); }
            @Override public String invoke(ToolInvocation command) { return "{\"answer\":\"observed\"}"; }
        });

        var result = gateway.invoke(invocation);

        assertEquals("knowledge_lookup", result.toolName());
        assertEquals("{\"answer\":\"observed\"}", result.output());
        assertEquals(1, result.attempts());
        assertTrue(result.latencyMs() >= 0);
    }

    @Test
    void unknownInternalToolFailsClosedWithoutGenericFallback() {
        ToolDescriptor descriptor = descriptor("not_registered", "LOW", "INTERNAL", false, true);
        ToolDispatcher dispatcher = new ToolDispatcher(
                new HttpToolInvoker(new ObjectMapper(), 1000, true, ""),
                new InternalToolHandlerRegistry(java.util.List.of()));

        IllegalStateException error = assertThrows(IllegalStateException.class,
                () -> dispatcher.dispatch(new ToolExecutionSpec(descriptor, null), invocation("not_registered", "x")));

        assertTrue(error.getMessage().contains("未知 INTERNAL 工具"));
    }

    @Test
    void nonIdempotentSideEffectUsesOneAttempt() {
        CountingCoordination coordination = new CountingCoordination();
        ResilientToolExecutor executor = new ResilientToolExecutor(new ToolCallValidator(), coordination);
        AtomicInteger calls = new AtomicInteger();
        ToolDescriptor descriptor = descriptor("destructive_operation", "HIGH", "INTERNAL", false, false);

        assertThrows(IllegalStateException.class,
                () -> executor.execute(descriptor, "{}", () -> {
                    calls.incrementAndGet();
                    throw new IllegalStateException("timeout");
                }));

        assertEquals(1, calls.get());
    }

    @Test
    void lowRiskReadOnlyRetriesOnceOnTimeout() {
        CountingCoordination coordination = new CountingCoordination();
        ResilientToolExecutor executor = new ResilientToolExecutor(new ToolCallValidator(), coordination);
        AtomicInteger calls = new AtomicInteger();
        ToolDescriptor descriptor = descriptor("read", "LOW", "INTERNAL", false, false);

        var result = executor.execute(descriptor, "{}", () -> {
            if (calls.incrementAndGet() == 1) throw new IllegalStateException("timeout");
            return "ok";
        });

        assertEquals("ok", result.value());
        assertEquals(2, result.attempts());
        assertEquals(2, calls.get());
    }

    @Test
    void localhostHttpPreservesAuthAndStableIdempotencyHeaders() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        CountDownLatch received = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/tool", exchange -> {
            requests.incrementAndGet();
            assertEquals("same-action-key", exchange.getRequestHeaders().getFirst("Idempotency-Key"));
            assertEquals("Bearer secret", exchange.getRequestHeaders().getFirst("X-Tool-Auth"));
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
            received.countDown();
        });
        server.start();
        try {
            String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/tool";
            ToolDescriptor descriptor = new ToolDescriptor("http", "http", "LOW", true, "HTTP", endpoint,
                    "X-Tool-Auth", "{}", Set.of(), Set.of(), true, true);
            String result = new HttpToolInvoker(new ObjectMapper(), 2000, true, "")
                    .invoke(new ToolExecutionSpec(descriptor, "Bearer secret"), "{}", "same-action-key");

            assertEquals("ok", result);
            assertTrue(received.await(1, TimeUnit.SECONDS));
            assertEquals(1, requests.get());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void cancellationInterruptsGatewayOwnedWorker() throws Exception {
        CountingCoordination coordination = new CountingCoordination();
        ResilientToolExecutor executor = new ResilientToolExecutor(new ToolCallValidator(), coordination);
        CountDownLatch started = new CountDownLatch(1);
        ToolDescriptor descriptor = descriptor("slow", "LOW", "INTERNAL", false, false);
        Thread owner = Thread.ofVirtual().start(() -> assertThrows(IllegalStateException.class,
                () -> executor.execute(descriptor, "{}", () -> {
                    started.countDown();
                    try {
                        Thread.sleep(Duration.ofSeconds(10));
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("cancelled", error);
                    }
                    return "unexpected";
                })));

        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertTrue(executor.cancel(owner));
        owner.join(2000);
        assertFalse(owner.isAlive());
    }

    @Test
    void tracePersistenceFailureDoesNotRepeatBusinessCall() {
        AtomicInteger calls = new AtomicInteger();
        ToolDescriptor descriptor = descriptor("read-once", "LOW", "INTERNAL", true, true);
        ToolGateway gateway = gateway(descriptor, new InternalToolHandler() {
            @Override public boolean supports(String toolName) { return "read-once".equals(toolName); }
            @Override public String invoke(ToolInvocation command) {
                calls.incrementAndGet();
                return "ok";
            }
        }, new ToolCallTracer(null) {
            @Override public void record(com.modelrag.toolgateway.trace.ToolCallTrace trace) {
                throw new IllegalStateException("trace store unavailable");
            }
        });

        var result = gateway.invoke(invocation("read-once", "{}"));

        assertEquals("ok", result.output());
        assertEquals(1, calls.get());
        assertEquals(List.of("tool-trace-unavailable"), result.degradedComponents());
    }

    private ToolGateway gateway(ToolDescriptor descriptor, InternalToolHandler handler) {
        return gateway(descriptor, handler, new ToolCallTracer(null) {
            @Override public void record(com.modelrag.toolgateway.trace.ToolCallTrace trace) { }
        });
    }

    private ToolGateway gateway(ToolDescriptor descriptor, InternalToolHandler handler, ToolCallTracer tracer) {
        ToolExecutionResolver resolver = name -> new ToolExecutionSpec(descriptor, null);
        ToolDispatcher dispatcher = new ToolDispatcher(new HttpToolInvoker(new ObjectMapper(), 1000, true, ""),
                new InternalToolHandlerRegistry(java.util.List.of(handler)));
        return new ToolGateway(resolver, new com.modelrag.toolgateway.policy.ToolAccessPolicy(),
                new ResilientToolExecutor(new ToolCallValidator(), new CountingCoordination()), dispatcher,
                tracer);
    }

    private ToolInvocation invocation(String name, String input) {
        return new ToolInvocation("execution-1", "execution-1:action:1", name, "user-1", Set.of("USER"),
                1, null, input, "execution-1:action:1", "trace-1");
    }

    private ToolDescriptor descriptor(String name, String risk, String type, boolean enabled, boolean idempotent) {
        return new ToolDescriptor(name, name, risk, true, type, null, null, "{}", Set.of(), Set.of(), idempotent, false);
    }

    private static final class CountingCoordination implements ToolCoordinationStore {
        private final Map<String, String> circuits = new ConcurrentHashMap<>();
        private final Map<String, Long> rates = new ConcurrentHashMap<>();

        @Override public void ensureAvailable() { }
        @Override public String circuit(String toolName) { return circuits.get(toolName); }
        @Override public void saveCircuit(String toolName, String state, int failures, long openedUntil, Duration ttl) {
            circuits.put(toolName, state + "|" + failures + "|" + openedUntil);
        }
        @Override public long incrementRate(String toolName, Duration window) {
            return rates.merge(toolName, 1L, Long::sum);
        }
    }
}
