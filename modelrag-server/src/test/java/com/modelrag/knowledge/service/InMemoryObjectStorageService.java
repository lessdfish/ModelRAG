package com.modelrag.knowledge.service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

@Service
@Profile("test")
public class InMemoryObjectStorageService implements ObjectStorageService {
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

    @Override
    public void put(String key, InputStream input, long length, String contentType) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream((int) Math.min(Integer.MAX_VALUE, Math.max(0, length)));
        input.transferTo(output);
        objects.put(key, output.toByteArray());
    }

    @Override
    public InputStream open(String key) {
        byte[] value = objects.get(key);
        if (value == null) throw new IllegalArgumentException("对象不存在: " + key);
        return new ByteArrayInputStream(value);
    }

    @Override
    public void delete(String key) {
        objects.remove(key);
    }
}
