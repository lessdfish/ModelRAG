package com.modelrag.api;

/** Replaceable boundary for protecting user-provided provider credentials. */
public interface SecretProtector {
    String protect(String plaintext);
    String reveal(String ciphertext);
    String mask(String plaintext);
}
