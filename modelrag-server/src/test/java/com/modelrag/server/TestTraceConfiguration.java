package com.modelrag.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.agent.trace.AgentStepTrace;
import com.modelrag.agent.trace.AgentStepTracer;
import com.modelrag.toolgateway.trace.ToolCallTrace;
import com.modelrag.toolgateway.trace.ToolCallTracer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("test")
class TestTraceConfiguration {
    @Bean AgentStepTracer agentStepTracer() { return new TestAgentStepTracer(); }
    @Bean ToolCallTracer toolCallTracer() { return new TestToolCallTracer(); }

    static final class TestAgentStepTracer extends AgentStepTracer {
        private final List<AgentStepTrace> values = new ArrayList<>();
        TestAgentStepTracer() { super(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class), new ObjectMapper()); }
        @Override public synchronized AgentStepTrace record(String executionId, String phase, String message, Map<String, Object> data, long latencyMs) {
            AgentStepTrace value = new AgentStepTrace(executionId, (int) values.stream().filter(item -> executionId.equals(item.executionId())).count() + 1, phase, message, "{}", "RUNNING", latencyMs, java.time.Instant.now().toString()); values.add(value); return value;
        }
        @Override public synchronized List<AgentStepTrace> list() { return List.copyOf(values); }
        @Override public synchronized List<AgentStepTrace> list(String executionId) { return values.stream().filter(item -> executionId.equals(item.executionId())).toList(); }
    }

    static final class TestToolCallTracer extends ToolCallTracer {
        private final List<ToolCallTrace> values = new ArrayList<>();
        TestToolCallTracer() { super(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry()); }
        @Override public synchronized void record(ToolCallTrace trace) { values.add(trace); }
        @Override public synchronized List<ToolCallTrace> list() { return List.copyOf(values); }
    }
}
