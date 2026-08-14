package com.modelrag.agent.controller;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.security.AccessControlService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** User-level memory governance. It controls retrieval without deleting user-owned memories. */
@RestController
@Profile("!test")
@RequestMapping("/api/v2/memory-settings")
public class MemorySettingsV2Controller {
    private final JdbcTemplate jdbc;
    private final AccessControlService access;

    public MemorySettingsV2Controller(JdbcTemplate jdbc, AccessControlService access) {
        this.jdbc = jdbc;
        this.access = access;
    }

    @GetMapping
    public ApiResponse<MemorySettings> get() {
        String userId = access.currentUser().id();
        return ApiResponse.success(jdbc.query("SELECT enabled,retention_days FROM kb_memory_setting WHERE user_id=?",
                (rs, n) -> new MemorySettings(rs.getBoolean(1), rs.getInt(2)), userId)
                .stream().findFirst().orElse(new MemorySettings(false, 180)));
    }

    @PutMapping
    public ApiResponse<MemorySettings> update(@Valid @RequestBody MemorySettingsRequest request) {
        String userId = access.currentUser().id();
        jdbc.update("""
                INSERT INTO kb_memory_setting(user_id,enabled,retention_days,update_time) VALUES (?,?,?,NOW())
                ON CONFLICT (user_id) DO UPDATE SET enabled=EXCLUDED.enabled,retention_days=EXCLUDED.retention_days,update_time=NOW()
                """, userId, request.enabled(), request.retentionDays());
        return ApiResponse.success(new MemorySettings(request.enabled(), request.retentionDays()));
    }

    public record MemorySettings(boolean enabled, int retentionDays) {}
    public record MemorySettingsRequest(boolean enabled, @Min(1) @Max(3650) int retentionDays) {}
}
