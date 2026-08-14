package com.modelrag.server;

import com.modelrag.agent.intent.IntentNode;
import com.modelrag.agent.intent.IntentTreeService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration(proxyBeanMethods = false)
@Profile("test")
class TestIntentTreeConfiguration {
    @Bean IntentTreeService intentTreeService() { return new TestIntentTreeService(); }

    static final class TestIntentTreeService extends IntentTreeService {
        private final AtomicLong ids = new AtomicLong();
        private final List<IntentNode> values = new ArrayList<>();
        TestIntentTreeService() { super(null); }
        @Override public synchronized IntentNode create(IntentNode node) { IntentNode saved = copy(node, ids.incrementAndGet()); values.add(saved); return saved; }
        @Override public synchronized IntentNode update(long datasetId, long id, IntentNode node) { values.removeIf(item -> item.id() == id); IntentNode saved = copy(node, id); values.add(saved); return saved; }
        @Override public synchronized void delete(long datasetId, long id) { values.removeIf(item -> item.id() == id); }
        @Override public synchronized List<IntentNode> list(long datasetId) { return values.stream().filter(item -> item.datasetId() == datasetId).sorted(Comparator.comparingInt(IntentNode::priority).reversed()).toList(); }
        @Override public synchronized Optional<IntentNode> match(long datasetId, String query) { return list(datasetId).stream().filter(IntentNode::enabled).filter(item -> query != null && query.contains(item.name())).findFirst(); }
        private IntentNode copy(IntentNode node, long id) { return new IntentNode(id, node.datasetId(), node.parentId(), node.name(), node.nodeType(), node.targetType(), node.targetId(), node.description(), node.priority(), node.enabled()); }
    }
}
