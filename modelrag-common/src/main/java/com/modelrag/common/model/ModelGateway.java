package com.modelrag.common.model;

/** Stable cross-module entry point for text generation. */
public interface ModelGateway {
    String generate(String prompt);
}
