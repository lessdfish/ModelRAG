package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.modelrag.search.channel.v2.MeasuredSemanticSearchPort;
import com.modelrag.search.channel.v2.PostgresSemanticSearchAdapter;
import com.modelrag.search.channel.v2.SemanticSearchRequest;
import com.modelrag.search.channel.v2.SemanticSearchTimeoutException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@EnabledIfEnvironmentVariable(named = "MODELRAG_RUN_G116_INTEGRATION", matches = "true")
class G116SemanticAnnIntegrationTest {
    private static final String USER = "modelrag";
    private static final String PASSWORD = "modelrag-test-password";

    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>("pgvector/pgvector:0.8.0-pg16")
            .withEnv("POSTGRES_DB", "modelrag")
            .withEnv("POSTGRES_USER", USER)
            .withEnv("POSTGRES_PASSWORD", PASSWORD)
            .withExposedPorts(5432)
            .waitingFor(Wait.forListeningPort())
            .withStartupTimeout(Duration.ofMinutes(2));

    private static JdbcTemplate jdbc;
    private static PostgresSemanticSearchAdapter adapter;
    private static String jdbcUrl;

    @BeforeAll
    static void prepare() {
        jdbcUrl = "jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432)
                + "/modelrag";
        DriverManagerDataSource dataSource = new DriverManagerDataSource(jdbcUrl, USER, PASSWORD);
        jdbc = new JdbcTemplate(dataSource);
        adapter = new PostgresSemanticSearchAdapter(jdbc,
                new DataSourceTransactionManager(dataSource), new ObjectMapper());
        jdbc.execute("CREATE EXTENSION IF NOT EXISTS vector");
        jdbc.execute("CREATE TABLE kb_dataset (id BIGINT PRIMARY KEY, delete_time TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE kb_document (id BIGINT PRIMARY KEY, dataset_id BIGINT NOT NULL, "
                + "active_version_id BIGINT, active_index_build_id BIGINT, delete_time TIMESTAMPTZ)");
        jdbc.execute("CREATE TABLE kb_index_build (id BIGINT PRIMARY KEY, dataset_id BIGINT NOT NULL, "
                + "document_id BIGINT NOT NULL, document_version_id BIGINT NOT NULL, state VARCHAR(30) NOT NULL)");
        jdbc.execute("CREATE TABLE kb_retrieval_unit (id BIGINT PRIMARY KEY, dataset_id BIGINT NOT NULL, "
                + "node_id BIGINT NOT NULL, document_id BIGINT NOT NULL, document_version_id BIGINT NOT NULL, "
                + "index_build_id BIGINT NOT NULL, unit_type VARCHAR(30) NOT NULL, title_path TEXT, "
                + "content TEXT NOT NULL, metadata JSONB NOT NULL)");
        jdbc.execute("CREATE TABLE kb_vector_embedding (id BIGSERIAL PRIMARY KEY, retrieval_unit_id BIGINT NOT NULL, "
                + "dataset_id BIGINT NOT NULL, document_id BIGINT NOT NULL, document_version_id BIGINT NOT NULL, "
                + "index_build_id BIGINT NOT NULL, embedding_profile VARCHAR(120) NOT NULL, embedding vector(3) NOT NULL)");
        jdbc.execute("CREATE INDEX idx_vector_embedding_hnsw ON kb_vector_embedding "
                + "USING hnsw (embedding vector_cosine_ops)");
        jdbc.update("INSERT INTO kb_dataset(id) VALUES (1),(2),(3)");
        document(11, 1, 101, 1001, 102, 1002);
        document(21, 2, 201, 2001, 202, 2002);
        document(31, 3, 301, 3001, 302, 3002);

        for (int index = 0; index < 20; index++) {
            unit(10_001 + index, 1, 11, 101, 1001, .20 + index * .01);
            unit(12_001 + index, 2, 21, 201, 2001, .00005 + index * .00001);
        }
        for (int index = 0; index < 80; index++) {
            unit(11_001 + index, 1, 11, 102, 1002, .0001 + index * .00001);
            unit(13_001 + index, 3, 31, 302, 3002, .0001 + index * .00001);
        }
        for (int index = 0; index < 5; index++) {
            unit(14_001 + index, 3, 31, 301, 3001, .30 + index * .01);
        }
        jdbc.execute("ANALYZE kb_vector_embedding");
    }

    @Test
    void annFirstRefillsPastStaleCandidatesAndPreservesIdentityAndRanking() {
        MeasuredSemanticSearchPort.MeasuredResult result = adapter.searchMeasured(
                new SemanticSearchRequest(1, new float[] {1, 0, 0}, "qwen3-v1", 20, 2_000));

        assertEquals(20, result.candidates().size());
        assertEquals(1, result.refillRounds());
        assertEquals(160, result.candidateBudget());
        assertFalse(result.refillExhausted());
        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.datasetId() == 1
                && candidate.documentId() == 11 && candidate.documentVersionId() == 101
                && candidate.indexBuildId() == 1001 && candidate.retrievalUnitId() < 11_000));
        assertEquals(List.copyOf(java.util.stream.LongStream.rangeClosed(10_001, 10_020)
                .boxed().toList()), result.candidates().stream().map(candidate -> candidate.retrievalUnitId()).toList());
    }

    @Test
    void refillExhaustionReturnsOnlyAvailableActiveCandidatesWithinBound() {
        MeasuredSemanticSearchPort.MeasuredResult result = adapter.searchMeasured(
                new SemanticSearchRequest(3, new float[] {1, 0, 0}, "qwen3-v1", 20, 2_000));

        assertEquals(5, result.candidates().size());
        assertEquals(3, result.refillRounds());
        assertEquals(640, result.candidateBudget());
        assertTrue(result.refillExhausted());
        assertTrue(result.candidates().stream().allMatch(candidate -> candidate.datasetId() == 3
                && candidate.documentVersionId() == 301 && candidate.indexBuildId() == 3001));
    }

    @Test
    void statementTimeoutCancelsBlockedQueryAndNextQueryCanProceed() throws Exception {
        long elapsedMs;
        try (Connection lock = DriverManager.getConnection(jdbcUrl, USER, PASSWORD);
                Statement statement = lock.createStatement()) {
            lock.setAutoCommit(false);
            statement.execute("LOCK TABLE kb_vector_embedding IN ACCESS EXCLUSIVE MODE");
            long started = System.nanoTime();
            assertThrows(SemanticSearchTimeoutException.class, () -> adapter.search(
                    new SemanticSearchRequest(1, new float[] {1, 0, 0}, "qwen3-v1", 1, 100)));
            elapsedMs = (System.nanoTime() - started) / 1_000_000;
            lock.rollback();
        }
        assertTrue(elapsedMs < 1_000, "database cancellation exceeded one second: " + elapsedMs);
        assertEquals(1, adapter.search(new SemanticSearchRequest(
                1, new float[] {1, 0, 0}, "qwen3-v1", 1, 2_000)).size());
    }

    private static void document(long documentId, long datasetId, long activeVersion, long activeBuild,
            long staleVersion, long staleBuild) {
        jdbc.update("INSERT INTO kb_document(id,dataset_id,active_version_id,active_index_build_id) VALUES (?,?,?,?)",
                documentId, datasetId, activeVersion, activeBuild);
        jdbc.update("INSERT INTO kb_index_build(id,dataset_id,document_id,document_version_id,state) "
                + "VALUES (?,?,?,?, 'ACTIVE'),(?,?,?,?, 'SUPERSEDED')",
                activeBuild, datasetId, documentId, activeVersion,
                staleBuild, datasetId, documentId, staleVersion);
    }

    private static void unit(long id, long datasetId, long documentId, long versionId,
            long buildId, double y) {
        jdbc.update("INSERT INTO kb_retrieval_unit(id,dataset_id,node_id,document_id,document_version_id,index_build_id,"
                        + "unit_type,title_path,content,metadata) VALUES (?,?,?,?,?,?,'PARAGRAPH','root',?, '{}'::jsonb)",
                id, datasetId, id, documentId, versionId, buildId, "unit-" + id);
        jdbc.update("INSERT INTO kb_vector_embedding(retrieval_unit_id,dataset_id,document_id,document_version_id,"
                        + "index_build_id,embedding_profile,embedding) VALUES (?,?,?,?,?,'qwen3-v1',CAST(? AS vector))",
                id, datasetId, documentId, versionId, buildId, "[1," + y + ",0]");
    }
}
