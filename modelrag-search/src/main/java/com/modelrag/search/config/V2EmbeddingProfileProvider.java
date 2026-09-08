package com.modelrag.search.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/** Supplies the configured embedding profile to every production V2 caller. */
@Component
public final class V2EmbeddingProfileProvider {
    public static final String DEFAULT_PROFILE = "qwen3-v1";

    private final String profile;

    @Autowired
    public V2EmbeddingProfileProvider(
            @Value("${modelrag.index.v2.embedding-profile:qwen3-v1}") String profile) {
        this.profile = profile == null || profile.isBlank() ? DEFAULT_PROFILE : profile.trim();
    }

    public V2EmbeddingProfileProvider() {
        this(DEFAULT_PROFILE);
    }

    public String profile() {
        return profile;
    }
}
