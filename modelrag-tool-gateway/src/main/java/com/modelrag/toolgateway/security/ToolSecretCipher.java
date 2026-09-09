package com.modelrag.toolgateway.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** AES-GCM compatibility cipher for kb_tool_definition.auth_header_value. */
@Component
@Profile("!test")
public class ToolSecretCipher {
    private static final String PREFIX = "{aes-gcm}";
    private static final int IV_BYTES = 12;
    private static final int TAG_BITS = 128;
    private final SecureRandom random = new SecureRandom();
    private final SecretKeySpec key;

    public ToolSecretCipher(@Value("${modelrag.security.tool-secret-key}") String secret) {
        if (secret == null || secret.isBlank()) {
            throw new IllegalStateException("modelrag.security.tool-secret-key must be supplied explicitly");
        }
        this.key = new SecretKeySpec(sha256(secret), "AES");
    }

    public String encrypt(String value) {
        if (value == null || value.isBlank() || value.startsWith(PREFIX)) return value;
        try {
            byte[] iv = new byte[IV_BYTES];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return PREFIX + Base64.getEncoder().encodeToString(payload);
        } catch (Exception error) {
            throw new IllegalStateException("工具密钥加密失败", error);
        }
    }

    public String decrypt(String value) {
        if (value == null || value.isBlank()) return value;
        if (!value.startsWith(PREFIX)) throw new IllegalStateException("工具密钥不是受支持的加密格式");
        try {
            byte[] payload = Base64.getDecoder().decode(value.substring(PREFIX.length()));
            if (payload.length <= IV_BYTES) throw new IllegalArgumentException("密文长度不合法");
            byte[] iv = Arrays.copyOfRange(payload, 0, IV_BYTES);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_BYTES, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (Exception error) {
            throw new IllegalStateException("工具密钥解密失败", error);
        }
    }

    private byte[] sha256(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            throw new IllegalStateException("无法生成工具密钥加密 Key", error);
        }
    }
}
