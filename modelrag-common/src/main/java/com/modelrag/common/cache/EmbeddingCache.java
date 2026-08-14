package com.modelrag.common.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.time.Duration;
import java.util.function.Function;

import org.springframework.stereotype.Service;

@Service
public class EmbeddingCache {
    private final Cache<String, float[]> values = Caffeine.<String, float[]>newBuilder().maximumWeight(64L * 1024 * 1024).weigher((String key, float[] value) -> Math.min(Integer.MAX_VALUE, key.length() * 2 + value.length * Float.BYTES)).expireAfterWrite(Duration.ofMinutes(30)).recordStats().build();

    public float[] get(String text, Function<String, float[]> loader) {
        return values.get(text, loader);
    }

    public float[] getIfPresent(String text) {
        return values.getIfPresent(text);
    }

    public void put(String text, float[] value) {
        values.put(text, value);
    }

    public long hits() {
        return values.stats().hitCount();
    }

    public long misses() {
        return values.stats().missCount();
    }
}
