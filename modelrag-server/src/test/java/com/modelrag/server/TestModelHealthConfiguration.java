package com.modelrag.server;

import com.modelrag.server.model.ModelHealthStore;
import com.modelrag.server.model.ModelHealthView;
import com.modelrag.server.model.ModelType;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("test")
class TestModelHealthConfiguration {
    @Bean
    ModelHealthStore modelHealthStore(MeterRegistry metrics) {
        return new TestModelHealthStore(metrics);
    }

    private static final class TestModelHealthStore extends ModelHealthStore {
        private final Map<String, Health> health = new ConcurrentHashMap<>();
        private final Map<String, Map<String, Object>> candidates = new ConcurrentHashMap<>();

        private TestModelHealthStore(MeterRegistry metrics) {
            super(org.mockito.Mockito.mock(org.springframework.jdbc.core.JdbcTemplate.class), 30, metrics);
        }

        @Override
        public Health state(ModelType type, String name) {
            return health.getOrDefault(key(type, name), new Health("CLOSED", 0, Instant.EPOCH));
        }

        @Override
        public boolean available(ModelType type, String name) {
            return !"OPEN".equals(state(type, name).state());
        }

        @Override
        public void success(ModelType type, String name) {
            health.put(key(type, name), new Health("CLOSED", 0, Instant.EPOCH));
        }

        @Override
        public void failure(ModelType type, String name) {
            Health old = state(type, name);
            health.put(key(type, name), new Health("OPEN", old.failures() + 1,
                    Instant.now().plusSeconds(30)));
        }

        @Override
        public boolean enabled(ModelType type, String name) {
            return !candidates.containsKey(key(type, name))
                    || Boolean.TRUE.equals(candidates.get(key(type, name)).get("enabled"));
        }

        @Override
        public int priority(ModelType type, String name) {
            return number(candidates.get(key(type, name)), "priority");
        }

        @Override
        public int canaryPercent(ModelType type, String name) {
            return number(candidates.get(key(type, name)), "canaryPercent");
        }

        @Override
        public ModelHealthView saveCandidate(String type, String name, String provider, int priority,
                boolean enabled, int canaryPercent) {
            ModelType modelType = ModelType.valueOf(type);
            ModelHealthView row = new ModelHealthView(modelType.name(), name,
                    provider == null || provider.isBlank() ? "local" : provider, state(modelType, name).state(),
                    priority, enabled, Math.max(0, Math.min(100, canaryPercent)), state(modelType, name).failures(),
                    Instant.EPOCH, false);
            candidates.put(key(modelType, name), new LinkedHashMap<>(Map.of("enabled", enabled,
                    "priority", priority, "canaryPercent", Math.max(0, Math.min(100, canaryPercent)))));
            return row;
        }

        @Override
        public void deleteCandidate(String configId) {
            candidates.remove(configId);
        }

        @Override
        public List<ModelHealthView> snapshot(List<com.modelrag.server.model.ModelClient> clients) {
            return candidates.entrySet().stream().map(entry -> {
                String[] parts = entry.getKey().split(":", 2);
                Map<String, Object> value = entry.getValue();
                ModelType type = ModelType.valueOf(parts[0]);
                Health current = state(type, parts[1]);
                return new ModelHealthView(type.name(), parts[1], "local", current.state(),
                        number(value, "priority"), Boolean.TRUE.equals(value.get("enabled")),
                        number(value, "canaryPercent"), current.failures(), current.nextProbeAt(), false);
            }).toList();
        }

        private static String key(ModelType type, String name) {
            return type.name() + ":" + name;
        }

        private static int number(Map<String, Object> row, String name) {
            return row != null && row.get(name) instanceof Number value ? value.intValue() : 0;
        }
    }
}
