package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.security.AccessControlService;
import com.modelrag.common.security.LocalAuthTokenService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.web.MockHttpServletRequest;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** PostgreSQL-backed proof that dataset ACLs are resolved from server-side grants. */
@Testcontainers(disabledWithoutDocker = true)
class G112AclIntegrationTest {
    @Container
    static final GenericContainer<?> POSTGRES = new GenericContainer<>("postgres:16-alpine")
            .withEnv("POSTGRES_DB", "modelrag_acl")
            .withEnv("POSTGRES_USER", "modelrag")
            .withEnv("POSTGRES_PASSWORD", "modelrag-test-password")
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort());

    private static JdbcTemplate jdbc;

    @BeforeAll
    static void prepareAclStore() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl("jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432)
                + "/modelrag_acl");
        dataSource.setUsername("modelrag");
        dataSource.setPassword("modelrag-test-password");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE kb_user_account(user_id VARCHAR(128) PRIMARY KEY, enabled BOOLEAN NOT NULL)");
        jdbc.execute("CREATE TABLE kb_user_role(user_id VARCHAR(128) NOT NULL, role_name VARCHAR(40) NOT NULL)");
        jdbc.execute("CREATE TABLE kb_dataset_acl(dataset_id BIGINT NOT NULL, user_id VARCHAR(128) NOT NULL, permission VARCHAR(20) NOT NULL)");
        jdbc.update("INSERT INTO kb_user_account(user_id,enabled) VALUES (?,TRUE)", "user-a");
        jdbc.update("INSERT INTO kb_user_role(user_id,role_name) VALUES (?,?)", "user-a", "USER");
        jdbc.update("INSERT INTO kb_dataset_acl(dataset_id,user_id,permission) VALUES (?,?,?)", 101L, "user-a", "READ");
    }

    @Test
    void userACanReadDatasetAButDatasetBIsDenied() {
        LocalAuthTokenService tokens = new LocalAuthTokenService("g11-2-acl-integration-secret", 900);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + tokens.issue("user-a", Set.of("USER"), Set.of(101L, 202L)));
        AccessControlService access = new AccessControlService(request, tokens, jdbc);

        assertTrue(access.currentUser().canAccess(101L));
        assertFalse(access.currentUser().canAccess(202L));
        access.requireDatasetAccess(101L);
        BusinessException denied = assertThrows(BusinessException.class, () -> access.requireDatasetAccess(202L));
        assertTrue(denied.errorCode() == ErrorCode.FORBIDDEN);

        writePassedEvidence();
    }

    private void writePassedEvidence() {
        Path repository = repositoryRoot();
        String commit = gitCommit(repository);
        assertFalse(commit.isBlank() || "unknown".equals(commit));
        Path artifact = repository.resolve("target").resolve("gate-evidence").resolve("acl-isolation.json");
        try {
            Files.createDirectories(artifact.getParent());
            new ObjectMapper().writerWithDefaultPrettyPrinter().writeValue(artifact.toFile(), Map.of(
                    "status", "PASSED",
                    "gitCommit", commit,
                    "testName", "G112AclIntegrationTest.userACanReadDatasetAButDatasetBIsDenied",
                    "executedAt", Instant.now().toString()));
        } catch (IOException error) {
            throw new IllegalStateException("cannot write ACL gate evidence", error);
        }
    }

    private Path repositoryRoot() {
        String multiModule = System.getProperty("maven.multiModuleProjectDirectory", "");
        Path current = multiModule.isBlank() ? Path.of("").toAbsolutePath() : Path.of(multiModule).toAbsolutePath();
        while (current != null && !Files.exists(current.resolve(".git"))) current = current.getParent();
        if (current == null) throw new IllegalStateException("repository root not found");
        return current.normalize();
    }

    private String gitCommit(Path repository) {
        try {
            Process process = new ProcessBuilder("git", "-c", "safe.directory=" + repository,
                    "-C", repository.toString(), "rev-parse", "HEAD").redirectErrorStream(true).start();
            String output = new String(process.getInputStream().readAllBytes()).trim();
            return process.waitFor() == 0 ? output : "unknown";
        } catch (IOException error) {
            return "unknown";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            return "unknown";
        }
    }
}
