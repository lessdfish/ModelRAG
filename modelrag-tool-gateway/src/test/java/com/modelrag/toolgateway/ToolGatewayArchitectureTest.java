package com.modelrag.toolgateway;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.catalog.ToolExecutionSpec;
import com.modelrag.toolgateway.catalog.ToolRegistrationCommand;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class ToolGatewayArchitectureTest {
    @Test
    void gatewaySourceDoesNotDependOnDownstreamModules() throws IOException {
        Path sourceRoot = Path.of("src", "main", "java");
        assertTrue(Files.isDirectory(sourceRoot));
        try (Stream<Path> files = Files.walk(sourceRoot)) {
            files.filter(path -> path.toString().endsWith(".java")).forEach(path -> {
                try {
                    String source = Files.readString(path);
                    assertFalse(source.contains("com.modelrag.agent."), path.toString());
                    assertFalse(source.contains("com.modelrag.qa."), path.toString());
                    assertFalse(source.contains("com.modelrag.search."), path.toString());
                    assertFalse(source.contains("com.modelrag.indexing."), path.toString());
                } catch (IOException error) {
                    throw new IllegalStateException(error);
                }
            });
        }
    }

    @Test
    void secretBearingCommandsRemainRedactedWhenRendered() {
        assertFalse(Arrays.stream(ToolDescriptor.class.getRecordComponents())
                .anyMatch(component -> component.getName().equals("authHeaderValue")));

        String secret = "Bearer gateway-secret";
        ToolExecutionSpec spec = new ToolExecutionSpec(
                new ToolDescriptor("tool", "tool", "LOW", true, "HTTP",
                        "https://example.com/tool", "Authorization", "{}", Set.of(), Set.of(), true, true),
                secret);
        ToolRegistrationCommand command = new ToolRegistrationCommand("tool", "tool", "LOW", true, "HTTP",
                "https://example.com/tool", "Authorization", secret, "{}", Set.of(), Set.of(), true);

        assertFalse(spec.toString().contains(secret));
        assertFalse(command.toString().contains(secret));
        assertTrue(spec.toString().contains("hasAuthSecret=true"));
        assertTrue(command.toString().contains("hasAuthSecret=true"));
    }
}
