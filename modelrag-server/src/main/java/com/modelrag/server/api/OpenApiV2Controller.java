package com.modelrag.server.api;

import java.io.IOException;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Static, versioned OpenAPI surface for the stable v2 contract. */
@RestController
public class OpenApiV2Controller {
    @GetMapping(value = "/api/v2/openapi.json", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> document() throws IOException {
        Resource resource = new ClassPathResource("openapi-v2.json");
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(resource);
    }

    @GetMapping(value = "/v3/api-docs", produces = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Resource> springCompatibleDocument() throws IOException {
        return document();
    }
}
