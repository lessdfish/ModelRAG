package com.modelrag.knowledge.service;

import java.io.IOException;
import java.io.InputStream;

/** Object storage is the source of truth for original files and normalized artifacts. */
public interface ObjectStorageService {
    void put(String objectKey, InputStream content, long contentLength, String contentType) throws IOException;

    InputStream open(String objectKey) throws IOException;

    void delete(String objectKey) throws IOException;
}
