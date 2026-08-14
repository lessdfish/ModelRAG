package com.modelrag.server.auth;

import com.modelrag.common.dto.ApiResponse;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.LocalAuthTokenService;
import com.modelrag.common.security.RequestUser;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Database-backed authentication. There is deliberately no local-admin or in-memory fallback. */
@RestController
@Profile("!test")
@RequestMapping({"/api/v1/auth", "/api/v2/auth"})
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final LocalAuthTokenService tokens;
    private final RefreshTokenService refreshTokens;
    private final AccessControlService access;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwords, LocalAuthTokenService tokens,
            RefreshTokenService refreshTokens, AccessControlService access) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.tokens = tokens;
        this.refreshTokens = refreshTokens;
        this.access = access;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest request) {
        if (request == null || blank(request.username()) || blank(request.password())) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户名或密码错误");
        }
        RequestUser user = loadUser(request.username().trim());
        if (!passwords.matches(request.password(), passwordHash(user.id()))) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户名或密码错误");
        }
        return ApiResponse.success(issue(user));
    }

    @PostMapping("/register")
    public ApiResponse<LoginResponse> register(@RequestBody RegisterRequest request) {
        if (request == null || blank(request.username()) || request.password() == null
                || request.password().length() < 12) {
            throw new BusinessException(ErrorCode.VALIDATION, "用户名不能为空，密码至少 12 位");
        }
        String userId = request.username().trim();
        if (!jdbc.queryForList("SELECT user_id FROM kb_user_account WHERE user_id=?", userId).isEmpty()) {
            throw new BusinessException(ErrorCode.VALIDATION, "用户已存在: " + userId);
        }
        String displayName = blank(request.displayName()) ? userId : request.displayName().trim();
        jdbc.update("INSERT INTO kb_user_account(user_id,display_name,password_hash,enabled) VALUES (?,?,?,TRUE)",
                userId, displayName, passwords.encode(request.password()));
        jdbc.update("INSERT INTO kb_user_role(user_id,role_name) VALUES (?,'USER')", userId);
        return ApiResponse.success(issue(new RequestUser(userId, Set.of("USER"), Set.of())));
    }

    @PostMapping("/refresh")
    public ApiResponse<LoginResponse> refresh(@RequestBody RefreshRequest request) {
        RequestUser user = refreshTokens.rotate(request == null ? null : request.refreshToken());
        return ApiResponse.success(issue(user));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(@RequestBody(required = false) RefreshRequest request) {
        refreshTokens.revoke(request == null ? null : request.refreshToken());
        return ApiResponse.success(null);
    }

    @GetMapping("/me")
    public ApiResponse<RequestUser> me() {
        return ApiResponse.success(access.currentUser());
    }

    public record LoginRequest(String username, String password) {}
    public record RegisterRequest(String username, String password, String displayName) {}
    public record RefreshRequest(String refreshToken) {}
    public record LoginResponse(String token, String refreshToken, RequestUser user) {}

    private LoginResponse issue(RequestUser user) {
        return new LoginResponse(tokens.issue(user.id(), user.roles(), user.datasetIds()),
                refreshTokens.issue(user.id()), user);
    }

    private RequestUser loadUser(String userId) {
        List<Boolean> enabled = jdbc.query("SELECT enabled FROM kb_user_account WHERE user_id=?",
                (rs, n) -> rs.getBoolean(1), userId);
        if (enabled.isEmpty()) throw new BusinessException(ErrorCode.FORBIDDEN, "用户名或密码错误");
        if (!enabled.get(0)) throw new BusinessException(ErrorCode.FORBIDDEN, "用户已禁用");
        Set<String> roles = jdbc.queryForList("SELECT role_name FROM kb_user_role WHERE user_id=?", String.class, userId)
                .stream().map(value -> value.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        java.util.Map<Long, String> permissions = jdbc.query(
                "SELECT dataset_id,permission FROM kb_dataset_acl WHERE user_id=? AND permission IN ('READ','WRITE','ADMIN')",
                rs -> {
                    java.util.Map<Long, String> result = new java.util.LinkedHashMap<>();
                    while (rs.next()) result.put(rs.getLong(1), rs.getString(2));
                    return result;
                }, userId);
        return new RequestUser(userId, roles.isEmpty() ? Set.of("USER") : roles,
                permissions.keySet(), permissions);
    }

    private String passwordHash(String userId) {
        String hash = jdbc.queryForObject("SELECT password_hash FROM kb_user_account WHERE user_id=?", String.class, userId);
        if (hash == null || !hash.startsWith("$argon2")) {
            throw new BusinessException(ErrorCode.FORBIDDEN, "用户凭据需要管理员重置");
        }
        return hash;
    }

    private boolean blank(String value) { return value == null || value.isBlank(); }
}
