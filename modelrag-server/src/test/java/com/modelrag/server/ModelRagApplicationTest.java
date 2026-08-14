package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class ModelRagApplicationTest {
    @Test
    void productionObjectMapperSerializesConversationTimestampsAsIso8601() throws Exception {
        String json = new ModelRagApplication().modelRagObjectMapper()
                .writeValueAsString(Instant.parse("2026-08-13T12:07:43.234314Z"));

        assertEquals("\"2026-08-13T12:07:43.234314Z\"", json);
    }

    @Test
    void productionPasswordEncoderCanActuallyHashAndVerify() {
        PasswordEncoder encoder = new ModelRagApplication().passwordEncoder();
        String raw = "production-password-16";

        String encoded = encoder.encode(raw);

        assertNotEquals(raw, encoded);
        assertTrue(encoded.startsWith("$argon2id$"));
        assertTrue(encoder.matches(raw, encoded));
    }
}
