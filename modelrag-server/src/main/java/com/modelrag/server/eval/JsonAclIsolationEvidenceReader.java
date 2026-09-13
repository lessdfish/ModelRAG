package com.modelrag.server.eval;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Reads the artifact emitted by the real ACL integration test. */
@Component
@Profile("!test")
public class JsonAclIsolationEvidenceReader implements AclIsolationEvidenceReader {
    private final ObjectMapper json;
    private final Path evidenceFile;

    public JsonAclIsolationEvidenceReader(ObjectMapper json,
            @Value("${modelrag.eval.acl-evidence-file:target/gate-evidence/acl-isolation.json}") String file) {
        this.json = json;
        this.evidenceFile = Path.of(file).toAbsolutePath().normalize();
    }

    @Override
    public AclIsolationEvidence read() {
        if (!Files.isRegularFile(evidenceFile)) {
            return AclIsolationEvidence.notRun("ACL isolation evidence not found");
        }
        try {
            JsonNode root = json.readTree(evidenceFile.toFile());
            return new AclIsolationEvidence(root.path("status").asText("NOT_RUN"),
                    root.path("gitCommit").asText("unknown"), root.path("testName").asText(""),
                    root.path("executedAt").asText(""), root.path("reason").asText(""));
        } catch (Exception error) {
            return AclIsolationEvidence.notRun("ACL isolation evidence unreadable");
        }
    }
}
