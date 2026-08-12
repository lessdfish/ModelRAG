package com.modelrag.common.security;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

@Service
public class LocalSecurityStore {
    private record User(String userId, String displayName, String passwordHash, boolean enabled,
            Instant createdAt, Instant updatedAt) {}

    private final ConcurrentMap<String, User> users = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<String>> roles = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Set<Long>> datasetIds = new ConcurrentHashMap<>();

    public List<Map<String, Object>> users() {
        return users.values().stream()
                .sorted(java.util.Comparator.comparing(User::userId))
                .map(user -> row(user.userId()))
                .toList();
    }

    public Optional<Map<String, Object>> user(String userId) {
        if (!users.containsKey(userId)) return Optional.empty();
        return Optional.of(row(userId));
    }

    public boolean exists(String userId) {
        return users.containsKey(userId);
    }

    public String passwordHash(String userId) {
        User user = users.get(userId);
        return user == null ? null : user.passwordHash();
    }

    public boolean enabled(String userId) {
        User user = users.get(userId);
        return user != null && user.enabled();
    }

    public Set<String> roles(String userId) {
        return roles.getOrDefault(userId, Set.of("USER"));
    }

    public Set<Long> datasetIds(String userId) {
        return datasetIds.getOrDefault(userId, Set.of());
    }

    public Map<String, Object> upsert(String userId, String displayName, String passwordHash, boolean enabled,
            Set<String> nextRoles) {
        Instant now = Instant.now();
        users.compute(userId, (id, old) -> {
            if (old == null) {
                if (passwordHash == null || passwordHash.isBlank()) throw new IllegalArgumentException("新用户密码不能为空");
                return new User(id, displayName, passwordHash, enabled, now, now);
            }
            String hash = passwordHash == null || passwordHash.isBlank() ? old.passwordHash() : passwordHash;
            return new User(id, displayName, hash, enabled, old.createdAt(), now);
        });
        roles.put(userId, normalizeRoles(nextRoles));
        return row(userId);
    }

    public Map<String, Object> grant(String userId, long datasetId) {
        ensureUser(userId);
        datasetIds.compute(userId, (ignored, old) -> {
            Set<Long> next = old == null ? ConcurrentHashMap.newKeySet() : ConcurrentHashMap.newKeySet(old.size() + 1);
            if (old != null) next.addAll(old);
            next.add(datasetId);
            return next;
        });
        return Map.of("userId", userId, "datasetId", datasetId, "permission", "READ");
    }

    public void revoke(String userId, long datasetId) {
        datasetIds.computeIfPresent(userId, (ignored, old) -> {
            Set<Long> next = ConcurrentHashMap.newKeySet(Math.max(1, old.size()));
            next.addAll(old);
            next.remove(datasetId);
            return next;
        });
    }

    private void ensureUser(String userId) {
        if (!users.containsKey(userId)) throw new IllegalArgumentException("用户不存在: " + userId);
    }

    private Map<String, Object> row(String userId) {
        User user = users.get(userId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("userId", userId);
        result.put("displayName", user == null ? userId : user.displayName());
        result.put("enabled", user == null || user.enabled());
        result.put("roles", new ArrayList<>(roles(userId)));
        result.put("datasetIds", new ArrayList<>(datasetIds(userId)));
        result.put("createdAt", user == null ? null : user.createdAt().toString());
        result.put("updatedAt", user == null ? null : user.updatedAt().toString());
        return result;
    }

    private Set<String> normalizeRoles(Set<String> value) {
        Set<String> normalized = value == null ? Set.of() : value.stream()
                .map(role -> Objects.toString(role, "").trim().toUpperCase(Locale.ROOT))
                .filter(role -> !role.isBlank())
                .collect(Collectors.toSet());
        return normalized.isEmpty() ? Set.of("USER") : normalized;
    }
}
