package com.modelrag.common.security;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/** Resolves identity only from a bearer token and PostgreSQL permissions. */
@Service
@Profile("!test")
public class AccessControlService {
    private final HttpServletRequest request;
    private final LocalAuthTokenService tokens;
    private final JdbcTemplate jdbc;

    @Autowired
    public AccessControlService(HttpServletRequest request, LocalAuthTokenService tokens, JdbcTemplate jdbc) {
        this.request = request;
        this.tokens = tokens;
        this.jdbc = jdbc;
    }

    public RequestUser currentUser() {
        String token = bearer();
        if (token == null || token.isBlank()) {
            throw forbidden("缺少 Authorization Bearer 登录令牌");
        }
        RequestUser tokenUser = tokens.parse(token);
        return databaseUser(tokenUser.id());
    }

    public RequestUser requireRole(String role) {
        RequestUser user = currentUser();
        if (!user.hasRole(role.toUpperCase(Locale.ROOT))) throw forbidden("用户缺少角色: " + role);
        return user;
    }

    public void requireDatasetAccess(long datasetId) {
        RequestUser user = currentUser();
        if (!user.canAccess(datasetId)) throw forbidden("用户无权访问知识库: " + datasetId);
    }

    public void requireDatasetWrite(long datasetId) {
        RequestUser user = currentUser();
        if (!user.canWrite(datasetId)) throw forbidden("用户无权修改知识库: " + datasetId);
    }

    public void requireDatasetAdmin(long datasetId) {
        RequestUser user = currentUser();
        if (!user.canAdminister(datasetId)) throw forbidden("用户无权管理知识库: " + datasetId);
    }

    public void requireAnyDatasetAccess(Set<Long> datasetIds) {
        RequestUser user = currentUser();
        if (user.roles().contains("ADMIN")) return;
        if (datasetIds.stream().noneMatch(user.datasetIds()::contains)) throw forbidden("用户没有可用知识库权限");
    }

    private RequestUser databaseUser(String userId) {
        try {
            var account = jdbc.query("SELECT enabled FROM kb_user_account WHERE user_id=?",
                    (rs, n) -> rs.getBoolean(1), userId);
            if (account.isEmpty()) throw forbidden("用户不存在");
            if (!account.get(0)) throw forbidden("用户已禁用: " + userId);
            Set<String> roles = jdbc.queryForList("SELECT role_name FROM kb_user_role WHERE user_id=?", String.class, userId)
                    .stream().map(role -> role.toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
            java.util.Map<Long, String> permissions = jdbc.query(
                    "SELECT dataset_id,permission FROM kb_dataset_acl WHERE user_id=? AND permission IN ('READ','WRITE','ADMIN')",
                    rs -> {
                        java.util.Map<Long, String> result = new java.util.LinkedHashMap<>();
                        while (rs.next()) result.put(rs.getLong(1), rs.getString(2));
                        return result;
                    }, userId);
            return new RequestUser(userId, roles.isEmpty() ? Set.of("USER") : roles,
                    permissions.keySet(), permissions);
        } catch (BusinessException error) {
            throw error;
        } catch (RuntimeException error) {
            throw new BusinessException(ErrorCode.DEPENDENCY_UNAVAILABLE, "用户权限存储暂时不可用");
        }
    }

    private String bearer() {
        String value = request.getHeader("Authorization");
        if (value == null || value.isBlank()) return null;
        return value.regionMatches(true, 0, "Bearer ", 0, 7) ? value.substring(7).trim() : null;
    }

    private BusinessException forbidden(String message) {
        return new BusinessException(ErrorCode.FORBIDDEN, message);
    }
}
