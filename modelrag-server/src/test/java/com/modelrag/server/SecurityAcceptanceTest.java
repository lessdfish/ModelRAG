package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = {
        "modelrag.security.default-admin-enabled=false",
        "modelrag.security.header-auth-enabled=false"
})
@AutoConfigureMockMvc
class SecurityAcceptanceTest {
    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper json;

    @Test
    void debugHeaderAuthCanBeDisabledWithoutBlockingBearerLogin() throws Exception {
        mockMvc.perform(get("/api/v1/knowledge-bases")
                        .header("X-User-Id", "debug-admin")
                        .header("X-User-Roles", "ADMIN"))
                .andExpect(status().isForbidden());

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"modelrag\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String token = json.readTree(body).path("data").path("token").asText();
        assertFalse(token.isBlank());

        mockMvc.perform(get("/api/v1/knowledge-bases")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
    }
}
