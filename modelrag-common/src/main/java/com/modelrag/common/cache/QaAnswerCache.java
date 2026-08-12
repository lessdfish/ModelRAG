package com.modelrag.common.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.time.Duration;
import java.util.function.Function;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Caffeine first, Redis second; Redis is optional for the no-infrastructure profile. */
@Service public class QaAnswerCache {
    public record Lookup<T>(T value, boolean hit) {}
    private final Cache<String,Object> local=Caffeine.newBuilder().maximumSize(500).expireAfterWrite(Duration.ofMinutes(10)).recordStats().build();
    private final ObjectProvider<StringRedisTemplate> redis; private final ObjectMapper json;
    public QaAnswerCache(ObjectProvider<StringRedisTemplate> redis,ObjectMapper json){this.redis=redis;this.json=json;}
    @SuppressWarnings("unchecked") public <T>T get(String key,Class<T> type,Function<String,T> loader){
        return getWithStatus(key,type,loader).value();
    }
    @SuppressWarnings("unchecked") public <T>Lookup<T> getWithStatus(String key,Class<T> type,Function<String,T> loader){
        Object localValue=local.getIfPresent(key); if(localValue!=null)return new Lookup<>((T)localValue,true);
        StringRedisTemplate template=redis.getIfAvailable();
        if(template!=null){try{String raw=template.opsForValue().get("modelrag:qa:"+key);if(raw!=null){T hit=json.readValue(raw,type);local.put(key,hit);return new Lookup<>(hit,true);}}catch(Exception ignored){/* cache failure never blocks QA */}}
        T value=loader.apply(key); local.put(key,value);
        if(template!=null){try{template.opsForValue().set("modelrag:qa:"+key,json.writeValueAsString(value),Duration.ofMinutes(10));}catch(Exception ignored){/* source result stays valid */}}
        return new Lookup<>(value,false);
    }
    public void invalidate(String key){local.invalidate(key);StringRedisTemplate template=redis.getIfAvailable();if(template!=null)try{template.delete("modelrag:qa:"+key);}catch(Exception ignored){}}
    public void invalidateDataset(long datasetId){String prefix=datasetId+":";local.asMap().keySet().removeIf(key->key.startsWith(prefix));StringRedisTemplate template=redis.getIfAvailable();if(template!=null)try{var keys=template.keys("modelrag:qa:"+prefix+"*");if(keys!=null&&!keys.isEmpty())template.delete(keys);}catch(Exception ignored){}}
    public long hits(){return local.stats().hitCount();} public long misses(){return local.stats().missCount();}
}
