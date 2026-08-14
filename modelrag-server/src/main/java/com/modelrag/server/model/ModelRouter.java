package com.modelrag.server.model;

import com.modelrag.common.model.ModelGateway;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class ModelRouter implements ModelGateway {
    private final List<ModelClient> clients;
    private final ModelHealthStore health;
    private final int canaryPercent;
    public ModelRouter(List<ModelClient> clients, ModelHealthStore health) { this(clients, health, 0); }
    @Autowired
    public ModelRouter(List<ModelClient> clients, ModelHealthStore health,
            @Value("${modelrag.model.canary-percent:0}") int canaryPercent) {
        this.clients = clients;
        this.health = health;
        this.canaryPercent = Math.max(0, Math.min(100, canaryPercent));
    }
    @Override public String generate(String prompt) { return execute(ModelType.CHAT, prompt); }
    @Override public void stream(String prompt, Consumer<String> consumer) {
        for (ModelClient client : orderedCandidates(ModelType.CHAT, prompt)) if (health.available(ModelType.CHAT, client.name())) try {
            client.stream(prompt, consumer);
            health.success(ModelType.CHAT, client.name());
            return;
        } catch (RuntimeException error) {
            health.failure(ModelType.CHAT, client.name());
        }
        throw new IllegalStateException("没有可用的 CHAT 模型客户端");
    }
    public String selectedClientName(ModelType type) {
        return orderedCandidates(type, "").stream()
                .filter(client -> health.available(type, client.name()))
                .findFirst()
                .map(ModelClient::name)
                .orElseThrow(() -> new IllegalStateException("没有可用的 " + type + " 模型客户端"));
    }
    public String execute(ModelType type, String input) {
        for (ModelClient client : orderedCandidates(type, input)) if (health.available(type, client.name())) try {
            String result = client.execute(input);
            health.success(type, client.name());
            return result;
        } catch (RuntimeException error) {
            health.failure(type, client.name());
        }
        throw new IllegalStateException("没有可用的 " + type + " 模型客户端");
    }
    private List<ModelClient> orderedCandidates(ModelType type, String input) {
        List<ModelClient> eligible = new ArrayList<>(clients.stream()
                .filter(client -> client.type() == type)
                .filter(client -> health.enabled(type, client.name()))
                .toList());
        eligible.sort(java.util.Comparator.comparingInt((ModelClient client) -> health.priority(type, client.name())).reversed());
        int canaryIndex = -1;
        int selectedCanaryPercent = canaryPercent;
        for (int i = 0; i < eligible.size(); i++) {
            int candidatePercent = Math.max(selectedCanaryPercent, health.canaryPercent(type, eligible.get(i).name()));
            if (eligible.get(i).name().contains("canary") && candidatePercent > 0) {
                canaryIndex = i;
                selectedCanaryPercent = candidatePercent;
                break;
            }
        }
        if (canaryIndex > 0 && Math.floorMod(input.hashCode(), 100) < selectedCanaryPercent) {
            ModelClient canary = eligible.remove(canaryIndex);
            eligible.add(0, canary);
        }
        return eligible;
    }
}
