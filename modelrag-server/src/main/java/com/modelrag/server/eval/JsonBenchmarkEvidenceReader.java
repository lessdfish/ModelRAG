package com.modelrag.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Reads benchmark evidence from immutable JSON reports; configuration is policy, not proof. */
@Component
@Profile("!test")
public class JsonBenchmarkEvidenceReader implements BenchmarkEvidenceReader {
    private final ObjectMapper json;
    private final Path reportDirectory;

    public JsonBenchmarkEvidenceReader(ObjectMapper json,
            @Value("${modelrag.eval.benchmark.report-directory:benchmarks/retrieval-scale/reports}") String directory) {
        this.json = json;
        this.reportDirectory = Path.of(directory).toAbsolutePath().normalize();
    }

    @Override
    public BenchmarkEvidence read(String benchmarkIdentity) {
        if (benchmarkIdentity == null || benchmarkIdentity.isBlank() || !Files.isDirectory(reportDirectory)) {
            return BenchmarkEvidence.notRun("benchmark report not found");
        }
        try (var files = Files.list(reportDirectory)) {
            List<Path> reports = files.filter(path -> path.getFileName().toString().endsWith(".json"))
                    .sorted(Comparator.reverseOrder()).toList();
            for (Path report : reports) {
                JsonNode root = json.readTree(report.toFile());
                String fixture = root.path("fixtureIdentity").asText("");
                String identity = root.path("benchmarkIdentity").asText(fixture);
                if (benchmarkIdentity.equals(identity) || benchmarkIdentity.equals(fixture)
                        || report.getFileName().toString().contains(benchmarkIdentity)) return parse(root);
            }
            return BenchmarkEvidence.notRun("matching benchmark report not found");
        } catch (Exception error) {
            return BenchmarkEvidence.notRun("benchmark report unreadable");
        }
    }

    private BenchmarkEvidence parse(JsonNode root) {
        Map<String, Long> topology = new LinkedHashMap<>();
        JsonNode shape = root.path("topology");
        for (String name : List.of("activeRetrievalUnits", "activeDocuments", "staleRetrievalUnits",
                "activeBuildCount", "activeBuildFilterLimit", "vectorDimension")) {
            topology.put(name, shape.path(name).asLong(0));
        }
        List<String> workloads = new ArrayList<>();
        root.path("workloads").forEach(value -> workloads.add(value.asText()));
        List<Integer> concurrencies = new ArrayList<>();
        root.path("results").forEach(value -> {
            int concurrency = value.path("concurrency").asInt(0);
            if (concurrency > 0 && !concurrencies.contains(concurrency)) concurrencies.add(concurrency);
        });
        JsonNode summary = root.path("summary");
        JsonNode correctness = root.path("correctness");
        return new BenchmarkEvidence(root.path("status").asText("NOT_RUN"), root.path("mode").asText("unknown"),
                root.path("gitCommit").asText("unknown"), root.path("fixtureIdentity").asText("unknown"),
                shape.path("sharedV2Index").asText("unknown"), topology, workloads, concurrencies,
                summary.path("p50Ms").asDouble(0), summary.path("p95Ms").asDouble(0),
                summary.path("p99Ms").asDouble(0), summary.path("errorRate").asDouble(1),
                summary.path("degradedRate").asDouble(1), summary.path("timeoutRate").asDouble(1),
                correctness.path("noStaleBuildLeakage").asBoolean(false),
                correctness.path("noAclLeakage").asBoolean(false),
                correctness.path("noActiveBuildTruncation").asBoolean(false),
                correctness.path("boundedResults").asBoolean(false),
                correctness.path("validEvidence").asBoolean(false), root.path("reason").asText(""));
    }
}
