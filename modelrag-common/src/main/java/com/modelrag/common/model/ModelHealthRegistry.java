package com.modelrag.common.model;

public interface ModelHealthRegistry {
    boolean available(String modelType, String modelName);
    void success(String modelType, String modelName);
    void failure(String modelType, String modelName);
}
