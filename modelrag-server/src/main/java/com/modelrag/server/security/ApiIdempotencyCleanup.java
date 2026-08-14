package com.modelrag.server.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Keeps completed API idempotency claims bounded without weakening the 24-hour replay window. */
@Component
@Profile("!test")
final class ApiIdempotencyCleanup {
    private static final Logger LOG = LoggerFactory.getLogger(ApiIdempotencyCleanup.class);
    private final JdbcTemplate jdbc;

    ApiIdempotencyCleanup(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Scheduled(cron = "${modelrag.idempotency.cleanup-cron:0 40 3 * * *}")
    void removeExpiredClaims() {
        try {
            int removed = jdbc.update("DELETE FROM kb_api_idempotency WHERE created_at < NOW() - INTERVAL '48 hours'");
            if (removed > 0) LOG.info("Removed {} expired API idempotency claims", removed);
        } catch (RuntimeException error) {
            LOG.error("Unable to clean expired API idempotency claims", error);
        }
    }
}
