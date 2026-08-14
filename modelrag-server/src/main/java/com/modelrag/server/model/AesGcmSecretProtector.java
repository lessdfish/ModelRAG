package com.modelrag.server.model;

import com.modelrag.api.SecretProtector;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** AES-256-GCM envelope value protection; the root key is external configuration only. */
@Component
public class AesGcmSecretProtector implements SecretProtector {
    private static final String PREFIX = "{aes256-gcm}";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public AesGcmSecretProtector(@Value("${modelrag.security.secret-root-key}") String rootKey) {
        if (rootKey == null || rootKey.isBlank()) {
            throw new IllegalStateException("modelrag.security.secret-root-key must be supplied explicitly");
        }
        this.key = new SecretKeySpec(sha256(rootKey), "AES");
    }

    @Override
    public String protect(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        if (plaintext.startsWith(PREFIX)) return plaintext;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("用户模型密钥加密失败", error);
        }
    }

    @Override
    public String reveal(String ciphertext) {
        if (ciphertext == null || ciphertext.isBlank()) return null;
        if (!ciphertext.startsWith(PREFIX)) {
            throw new IllegalStateException("用户模型密钥不是受支持的加密格式");
        }
        try {
            byte[] payload = Base64.getDecoder().decode(ciphertext.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) throw new IllegalArgumentException("密文长度不合法");
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("用户模型密钥解密失败", error);
        }
    }

    @Override
    public String mask(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) return null;
        String value = plaintext.trim();
        return value.length() <= 4 ? "****" : "****" + value.substring(value.length() - 4);
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成模型密钥保护 Key", error);
        }
    }
}
