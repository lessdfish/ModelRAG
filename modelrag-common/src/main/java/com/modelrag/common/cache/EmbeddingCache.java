package com.modelrag.common.cache;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.stereotype.Service;
@Service public class EmbeddingCache { private final Cache<String,float[]> values=Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(30)).recordStats().build(); public float[] get(String text,Function<String,float[]> loader){return values.get(text,loader);} public long hits(){return values.stats().hitCount();} public long misses(){return values.stats().missCount();} }
