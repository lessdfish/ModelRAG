package com.modelrag.server.auth;

import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the first database-backed administrator only from an explicit external bootstrap secret. */
@Component
@Profile("!test")
public class InitialAdminBootstrap implements ApplicationRunner {
    private static final Logger LOG = LoggerFactory.getLogger(InitialAdminBootstrap.class);
    private static final Pattern SAFE_USER = Pattern.compile("[A-Za-z0-9._@-]{3,100}");
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwords;
    private final String userId;
    private final String password;

    public InitialAdminBootstrap(JdbcTemplate jdbc, PasswordEncoder passwords,
            @Value("${modelrag.security.bootstrap-admin-user:}") String userId,
            @Value("${modelrag.security.bootstrap-admin-password:}") String password) {
        this.jdbc = jdbc;
        this.passwords = passwords;
        this.userId = userId == null ? "" : userId.trim();
        this.password = password == null ? "" : password;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments arguments) {
        Integer administrators = jdbc.queryForObject("""
                SELECT count(*) FROM kb_user_account account
                JOIN kb_user_role role ON role.user_id=account.user_id
                WHERE role.role_name='ADMIN' AND account.enabled=TRUE
                """, Integer.class);
        if (administrators != null && administrators > 0) return;
        if (!SAFE_USER.matcher(userId).matches() || password.length() < 16) {
            throw new IllegalStateException("首次启动必须通过 MODELRAG_BOOTSTRAP_ADMIN_USER 和至少 16 位的 "
                    + "MODELRAG_BOOTSTRAP_ADMIN_PASSWORD 创建管理员");
        }
        jdbc.update("""
                INSERT INTO kb_user_account(user_id,display_name,password_hash,enabled,update_time)
                VALUES (?, ?, ?, TRUE, NOW())
                ON CONFLICT(user_id) DO UPDATE SET password_hash=EXCLUDED.password_hash,
                    enabled=TRUE,update_time=NOW()
                """, userId, userId, passwords.encode(password));
        jdbc.update("""
                INSERT INTO kb_user_role(user_id,role_name) VALUES (?,'ADMIN'),(?,'APPROVER')
                ON CONFLICT(user_id,role_name) DO NOTHING
                """, userId, userId);
        LOG.info("Created initial database administrator userId={}", userId);
    }
}
