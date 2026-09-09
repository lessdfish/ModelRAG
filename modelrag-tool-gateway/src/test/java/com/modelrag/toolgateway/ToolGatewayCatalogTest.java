package com.modelrag.toolgateway;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.toolgateway.catalog.ToolDescriptor;
import com.modelrag.toolgateway.catalog.ToolRegistrationCommand;
import com.modelrag.toolgateway.execution.ToolInvocation;
import com.modelrag.toolgateway.policy.ToolAccessPolicy;
import com.modelrag.toolgateway.policy.ToolRiskPolicy;
import com.modelrag.toolgateway.security.ToolSecretCipher;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Set;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.junit.jupiter.api.Test;

class ToolGatewayCatalogTest {
    @Test
    void newCipherDecryptsCiphertextWrittenByTheLegacyAesGcmFormat() throws Exception {
        String secret = "legacy-tool-secret";
        String value = "TOP-SECRET-TOOL-CREDENTIAL";
        byte[] iv = new byte[12];
        for (int index = 0; index < iv.length; index++) iv[index] = (byte) index;
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(sha256(secret), "AES"),
                new GCMParameterSpec(128, iv));
        byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
        byte[] payload = new byte[iv.length + encrypted.length];
        System.arraycopy(iv, 0, payload, 0, iv.length);
        System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
        String legacyCiphertext = "{aes-gcm}" + Base64.getEncoder().encodeToString(payload);

        ToolSecretCipher gatewayCipher = new ToolSecretCipher(secret);

        assertEquals(value, gatewayCipher.decrypt(legacyCiphertext));
        assertEquals(value, gatewayCipher.decrypt(gatewayCipher.encrypt(value)));
        assertNotEquals(gatewayCipher.encrypt(value), gatewayCipher.encrypt(value));
    }

    @Test
    void descriptorHasNoSecretBearingFieldOrValue() {
        ToolDescriptor descriptor = new ToolDescriptor("http-tool", "safe", "LOW", true, "HTTP",
                "https://api.example.com/tool", "Authorization", "{}", Set.of("USER"), Set.of(7L), true,
                true);

        assertTrue(Set.of(ToolDescriptor.class.getRecordComponents()).stream()
                .noneMatch(component -> component.getName().equals("authHeaderValue")));
        assertTrue(!descriptor.toString().contains("TOP-SECRET-TOOL-CREDENTIAL"));
    }

    @Test
    void accessAndRiskPoliciesAreDeterministic() {
        ToolDescriptor tool = new ToolDescriptor("write", "write", "HIGH", true, "HTTP",
                "https://api.example.com/tool", null, "{}", Set.of("OPS"), Set.of(7L), false, false);
        ToolInvocation allowed = new ToolInvocation("execution", "execution:action:1", "write", "user",
                Set.of("ops"), 7, null, "{}", "execution:action:1", "trace");
        ToolInvocation wrongDataset = new ToolInvocation("execution", "execution:action:1", "write", "user",
                Set.of("OPS"), 8, null, "{}", "execution:action:1", "trace");
        ToolAccessPolicy access = new ToolAccessPolicy();
        ToolRiskPolicy risk = new ToolRiskPolicy();

        assertTrue(access.allowed(tool, allowed));
        assertThrows(RuntimeException.class, () -> access.requireAllowed(tool, wrongDataset));
        assertTrue(risk.requiresApproval(tool));
        assertTrue(!risk.safeToRetry(tool));
        assertTrue(!risk.safeToReplayAfterCrash(tool));
    }

    @Test
    void writeCommandKeepsSecretOnlyOnTheWriteSide() {
        ToolRegistrationCommand command = new ToolRegistrationCommand("http-tool", "safe", "LOW", true,
                "HTTP", "https://api.example.com/tool", "Authorization", "TOP-SECRET-TOOL-CREDENTIAL",
                "{}", Set.of(), Set.of(), true);

        assertEquals("TOP-SECRET-TOOL-CREDENTIAL", command.authHeaderValue());
        ToolDescriptor descriptor = new ToolDescriptor(command.name(), command.description(), command.riskLevel(),
                command.enabled(), command.type(), command.endpoint(), command.authHeaderName(), command.jsonSchema(),
                command.allowedRoles(), command.allowedDatasetIds(), command.idempotent(), true);
        assertTrue(!descriptor.toString().contains(command.authHeaderValue()));
    }

    private static byte[] sha256(String value) throws Exception {
        return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    }
}
