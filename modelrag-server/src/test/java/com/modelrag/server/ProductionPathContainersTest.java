package com.modelrag.server;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.zaxxer.hikari.HikariDataSource;
import com.modelrag.common.vector.SearchRequest;
import com.modelrag.indexing.outbox.ElasticsearchOutboxConsumer;
import com.modelrag.indexing.outbox.PostgresIndexOutbox;
import com.modelrag.indexing.store.PostgresVectorStore;
import com.modelrag.indexing.service.EmbeddingService;
import com.modelrag.common.vector.SearchResult;
import com.modelrag.agent.memory.LongTermMemoryService;
import com.modelrag.agent.memory.ConversationMemory;
import com.modelrag.knowledge.service.DocumentDeletionService;
import com.modelrag.knowledge.service.PostgresKnowledgeStore;
import com.modelrag.knowledge.service.DocumentService;
import com.modelrag.knowledge.service.ObjectStorageService;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.modelrag.common.operation.RedisOperationGuard;
import com.modelrag.common.rate.DatasetRateLimiter;
import com.modelrag.server.health.ObjectStorageHealthIndicator;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CreateBucketRequest;
import software.amazon.awssdk.services.s3.model.BucketVersioningStatus;
import software.amazon.awssdk.services.s3.model.GetBucketVersioningRequest;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.PutBucketVersioningRequest;
import software.amazon.awssdk.services.s3.model.VersioningConfiguration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.ImageFromDockerfile;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Opt-in smoke test for the production dependency topology. It is disabled by default so ordinary
 * unit/acceptance tests never silently pull images or require a local Docker daemon.
 */
@Testcontainers
@EnabledIfEnvironmentVariable(named = "MODELRAG_RUN_PRODUCTION_INTEGRATION", matches = "true")
@ActiveProfiles("production")
@SpringBootTest(properties = {
        "spring.profiles.active=production",
        "modelrag.ollama.enabled=false",
        "modelrag.reranker.enabled=false",
        "modelrag.outbox.poll-ms=3600000"
})
class ProductionPathContainersTest {
    private static final String ELASTICSEARCH_VERSION = "8.15.0";
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PostgresVectorStore vectors;

    @Autowired
    private S3Client s3;

    @Autowired
    private ObjectStorageHealthIndicator objectStorageHealth;

    @Autowired
    private PostgresIndexOutbox outbox;

    @Autowired
    private ElasticsearchOutboxConsumer outboxConsumer;

    @Autowired
    private DocumentDeletionService deletions;

    @Autowired
    private ObjectStorageService objectStorage;

    @Autowired
    private DatasetRateLimiter rateLimiter;

    @Autowired
    private RedisOperationGuard operationGuard;

    @Autowired
    private ConversationMemory conversationMemory;

    @Container
    static final GenericContainer<?> postgres = new GenericContainer<>("pgvector/pgvector:0.8.0-pg16")
            .withEnv("POSTGRES_DB", "modelrag")
            .withEnv("POSTGRES_USER", "modelrag")
            .withEnv("POSTGRES_PASSWORD", "modelrag-test-password")
            .withExposedPorts(5432).waitingFor(Wait.forListeningPort());

    @Container
    static final GenericContainer<?> elasticsearch = new GenericContainer<>(new ImageFromDockerfile()
            .withDockerfileFromBuilder(builder -> builder
                    .from("docker.elastic.co/elasticsearch/elasticsearch:" + ELASTICSEARCH_VERSION)
                    .run("bin/elasticsearch-plugin install --batch analysis-smartcn")
                    .build()))
            .withEnv("discovery.type", "single-node")
            .withEnv("xpack.security.enabled", "false")
            .withEnv("ES_JAVA_OPTS", "-Xms512m -Xmx512m")
            .withExposedPorts(9200).waitingFor(Wait.forListeningPort());

    @Container
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379).waitingFor(Wait.forListeningPort());

    @Container
    static final GenericContainer<?> minio = new GenericContainer<>("minio/minio:RELEASE.2024-08-17T01-24-54Z")
            .withCommand("server", "/data")
            .withEnv("MINIO_ROOT_USER", "modelrag-test")
            .withEnv("MINIO_ROOT_PASSWORD", "modelrag-test-password")
            .withExposedPorts(9000).waitingFor(Wait.forListeningPort());

    @DynamicPropertySource
    static void productionProperties(DynamicPropertyRegistry properties) {
        properties.add("spring.datasource.url", () -> "jdbc:postgresql://" + postgres.getHost() + ":"
                + postgres.getMappedPort(5432) + "/modelrag?connectTimeout=2&socketTimeout=2");
        properties.add("spring.datasource.username", () -> "modelrag");
        properties.add("spring.datasource.password", () -> "modelrag-test-password");
        properties.add("spring.datasource.hikari.connection-timeout", () -> "2000");
        properties.add("spring.data.redis.host", redis::getHost);
        properties.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        properties.add("spring.data.redis.connect-timeout", () -> "500ms");
        properties.add("spring.data.redis.timeout", () -> "500ms");
        properties.add("modelrag.elasticsearch.endpoint", () -> "http://" + elasticsearch.getHost() + ":"
                + elasticsearch.getMappedPort(9200));
        properties.add("modelrag.storage.endpoint", () -> "http://" + minio.getHost() + ":"
                + minio.getMappedPort(9000));
        properties.add("modelrag.storage.bucket", () -> "modelrag");
        properties.add("modelrag.storage.access-key", () -> "modelrag-test");
        properties.add("modelrag.storage.secret-key", () -> "modelrag-test-password");
        properties.add("modelrag.security.token-secret", () -> "test-token-secret-for-production-smoke");
        properties.add("modelrag.security.secret-root-key", () -> "test-secret-root-key-for-production-smoke");
        properties.add("modelrag.security.tool-secret-key", () -> "test-tool-secret-key-for-production-smoke");
        properties.add("modelrag.security.bootstrap-admin-user", () -> "integration-admin");
        properties.add("modelrag.security.bootstrap-admin-password", () -> "integration-admin-password-123");
        migrateSchemaBeforeApplicationStartup();
    }

    private static void migrateSchemaBeforeApplicationStartup() {
        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/modelrag");
            dataSource.setUsername("modelrag");
            dataSource.setPassword("modelrag-test-password");
            Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        }
    }

    @Test
    void productionDependencyContainersAreReachableAndSchemaIsMigrated() throws Exception {
        assertTrue(postgres.isRunning());
        assertTrue(elasticsearch.isRunning());
        assertTrue(redis.isRunning());
        assertTrue(minio.isRunning());
        s3.createBucket(CreateBucketRequest.builder().bucket("modelrag").build());
        s3.putBucketVersioning(PutBucketVersioningRequest.builder().bucket("modelrag")
                .versioningConfiguration(VersioningConfiguration.builder()
                        .status(BucketVersioningStatus.ENABLED).build())
                .build());
        assertEquals(BucketVersioningStatus.ENABLED,
                s3.getBucketVersioning(GetBucketVersioningRequest.builder().bucket("modelrag").build()).status());
        assertEquals("UP", objectStorageHealth.health().getStatus().getCode());

        try (HikariDataSource dataSource = new HikariDataSource()) {
            dataSource.setJdbcUrl("jdbc:postgresql://" + postgres.getHost() + ":" + postgres.getMappedPort(5432) + "/modelrag");
            dataSource.setUsername("modelrag");
            dataSource.setPassword("modelrag-test-password");
            org.springframework.jdbc.core.JdbcTemplate jdbc = new org.springframework.jdbc.core.JdbcTemplate(dataSource);
            assertEquals("0.8.0", jdbc.queryForObject("SELECT extversion FROM pg_extension WHERE extname='vector'", String.class));
            assertEquals(0, jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name IN ('kb_ab_event','kb_ab_experiment')", Integer.class));
            assertTrue(jdbc.queryForObject("SELECT count(*) FROM information_schema.tables WHERE table_name='kb_index_outbox'", Integer.class) > 0);
            assertEquals(1, jdbc.queryForObject("""
                    SELECT count(*) FROM kb_user_account account
                    JOIN kb_user_role role ON role.user_id=account.user_id
                    WHERE account.user_id='integration-admin' AND account.enabled=TRUE
                        AND role.role_name='ADMIN'
                    """, Integer.class));

            long datasetId = jdbc.queryForObject("INSERT INTO kb_dataset(name,embedding_model,embedding_dimensions,embedding_profile_version) VALUES ('fixture-ann','Qwen3-Embedding-0.6B',1024,'qwen3-embedding-0.6b-1024-v1') RETURNING id", Long.class);
            long documentId = jdbc.queryForObject("INSERT INTO kb_document(dataset_id,file_name,file_type,file_size,file_hash,index_status,version,active_index_version,index_state) VALUES (?, 'fixture.txt','txt',1,'fixture','READY',1,1,'READY') RETURNING id", Long.class, datasetId);
            String target = unitVector(0);
            String distractor = unitVector(1);
            long firstChunkId = 0;
            for (int index = 0; index < 128; index++) {
                Long chunkId = jdbc.queryForObject("INSERT INTO kb_chunk(document_id,dataset_id,chunk_index,content,embedding,version) VALUES (?,?,?, ?,CAST(? AS vector),1) RETURNING id",
                        Long.class, documentId, datasetId, index, "fixture-" + index, index == 0 ? target : distractor);
                if (index == 0) firstChunkId = chunkId;
            }
            float[] query = new float[1024];
            query[0] = 1;
            SearchRequest request = new SearchRequest(datasetId, query, 5);
            List<SearchResult> exact = vectors.exactSearch(request);
            List<SearchResult> ann = vectors.search(request);
            Set<Long> exactIds = exact.stream().map(SearchResult::chunkId).collect(Collectors.toSet());
            Set<Long> annIds = ann.stream().map(SearchResult::chunkId).collect(Collectors.toSet());
            assertFalse(exact.isEmpty());
            assertFalse(ann.isEmpty());
            assertTrue(annIds.contains(exact.get(0).chunkId()), "ANN result must retain exact top-1 under active filtering");
            assertTrue(annIds.stream().anyMatch(exactIds::contains));
            verifyConcurrentVectorQueries(datasetId);

            PostgresKnowledgeStore routing = new PostgresKnowledgeStore(jdbc, (ignored, text) -> {
                float[] embedding = new float[1024];
                embedding[0] = 1;
                return embedding;
            });
            java.util.LinkedHashSet<Long> allowed = new java.util.LinkedHashSet<>();
            java.util.LinkedHashSet<Long> expectedTopThree = new java.util.LinkedHashSet<>();
            for (int index = 0; index < 100; index++) {
                boolean targetDataset = index >= 97;
                long routedDatasetId = jdbc.queryForObject("""
                        INSERT INTO kb_dataset(name,description,embedding_model,embedding_dimensions,
                            embedding_profile_version,routing_embedding)
                        VALUES (?,?,'Qwen3-Embedding-0.6B',1024,'qwen3-embedding-0.6b-1024-v1',CAST(? AS vector))
                        RETURNING id
                        """, Long.class, targetDataset ? "needle-target-" + index : "corpus-" + index,
                        targetDataset ? "needle target policy" : "unrelated corpus", targetDataset ? target : distractor);
                jdbc.update("""
                        INSERT INTO kb_document(dataset_id,file_name,file_type,file_size,file_hash,index_status,
                            version,active_index_version,index_state)
                        VALUES (?,?,'txt',1,?,'READY',1,1,'READY')
                        """, routedDatasetId, "routing-" + index + ".txt", "routing-hash-" + index);
                allowed.add(routedDatasetId);
                if (targetDataset) expectedTopThree.add(routedDatasetId);
            }
            List<com.modelrag.knowledge.model.Dataset> routed = routing.routeDatasets(
                    "needle target policy", allowed, 3);
            assertEquals(3, routed.size());
            assertEquals(expectedTopThree,
                    routed.stream().map(com.modelrag.knowledge.model.Dataset::id)
                            .collect(Collectors.toCollection(java.util.LinkedHashSet::new)));

            verifyVersionedOutboxAliasAndDeferredDeletion(jdbc, datasetId, documentId, firstChunkId);
            verifyLongTermMemoryGovernance(jdbc, datasetId);
            verifyConversationJsonbPersistence(jdbc, datasetId);
            verifyConcurrentDuplicateUploadIsAtomic(jdbc, datasetId);
            verifyPostgresBackupAndRestoreDrill();
        }
        HttpClient http = HttpClient.newHttpClient();
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200) + "/_cluster/health")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        String es = "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200);
        String mapping = "{\"mappings\":{\"properties\":{\"content\":{\"type\":\"text\",\"analyzer\":\"smartcn\"}}}}";
        assertTrue(http.send(HttpRequest.newBuilder(URI.create(es + "/modelrag-smartcn-smoke"))
                .header("Content-Type", "application/json").PUT(HttpRequest.BodyPublishers.ofString(mapping)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode() < 300);
        String alias = "{\"actions\":[{\"add\":{\"index\":\"modelrag-smartcn-smoke\",\"alias\":\"modelrag-smartcn-active\"}}]}";
        assertTrue(http.send(HttpRequest.newBuilder(URI.create(es + "/_aliases"))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(alias)).build(),
                HttpResponse.BodyHandlers.discarding()).statusCode() < 300);
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(es + "/modelrag-smartcn-active"))
                .method("HEAD", HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://" + minio.getHost() + ":" + minio.getMappedPort(9000) + "/minio/health/ready")).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode());
        verifyDependencyFaultBoundaries(http);
    }

    private void verifyDependencyFaultBoundaries(HttpClient http) throws Exception {
        redis.getDockerClient().stopContainerCmd(redis.getContainerId()).withTimeout(5).exec();
        rateLimiter.check(1L);
        BusinessException redisFailure = assertThrows(BusinessException.class,
                operationGuard::requireAvailableForSideEffect);
        assertEquals(ErrorCode.DEPENDENCY_UNAVAILABLE, redisFailure.errorCode());
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));
        assertEquals(200, http.send(HttpRequest.newBuilder(URI.create("http://" + elasticsearch.getHost() + ":"
                + elasticsearch.getMappedPort(9200) + "/_cluster/health")).GET().build(),
                HttpResponse.BodyHandlers.discarding()).statusCode());

        elasticsearch.getDockerClient().stopContainerCmd(elasticsearch.getContainerId()).withTimeout(5).exec();
        long faultDatasetId = jdbc.queryForObject("""
                INSERT INTO kb_dataset(name,embedding_model,embedding_dimensions,embedding_profile_version)
                VALUES ('fault-vector','Qwen3-Embedding-0.6B',1024,'qwen3-embedding-0.6b-1024-v1') RETURNING id
                """, Long.class);
        long faultDocumentId = jdbc.queryForObject("""
                INSERT INTO kb_document(dataset_id,file_name,file_type,file_size,file_hash,index_status,
                    version,active_index_version,index_state)
                VALUES (?,'fault.txt','txt',1,'fault-vector','READY',1,1,'READY') RETURNING id
                """, Long.class, faultDatasetId);
        jdbc.update("""
                INSERT INTO kb_chunk(document_id,dataset_id,chunk_index,content,embedding,version)
                VALUES (?,?,0,'vector path survives Elasticsearch outage',CAST(? AS vector),1)
                """, faultDocumentId, faultDatasetId, unitVector(0));
        float[] queryVector = new float[1024];
        queryVector[0] = 1;
        assertFalse(vectors.exactSearch(new SearchRequest(faultDatasetId, queryVector, 1)).isEmpty(),
                "pgvector retrieval must remain available when Elasticsearch is down");

        minio.getDockerClient().stopContainerCmd(minio.getContainerId()).withTimeout(5).exec();
        assertEquals("DOWN", objectStorageHealth.health().getStatus().getCode());
        assertEquals(1, jdbc.queryForObject("SELECT 1", Integer.class));

        postgres.getDockerClient().stopContainerCmd(postgres.getContainerId()).withTimeout(5).exec();
        assertThrows(org.springframework.dao.DataAccessException.class,
                () -> jdbc.queryForObject("SELECT 1", Integer.class));
    }

    private void verifyConcurrentVectorQueries(long datasetId) throws Exception {
        float[] query = new float[1024];
        query[0] = 1;
        List<Long> latencies = new java.util.concurrent.CopyOnWriteArrayList<>();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var queries = java.util.stream.IntStream.range(0, 64)
                    .mapToObj(index -> executor.submit(() -> {
                        long started = System.nanoTime();
                        assertFalse(vectors.search(new SearchRequest(datasetId, query, 5)).isEmpty());
                        latencies.add((System.nanoTime() - started) / 1_000_000);
                    })).toList();
            for (var queryResult : queries) queryResult.get(10, java.util.concurrent.TimeUnit.SECONDS);
        }
        List<Long> sorted = latencies.stream().sorted().toList();
        long p95 = sorted.get((int) Math.ceil(sorted.size() * .95) - 1);
        System.out.println("PRODUCTION_PGVECTOR_CONCURRENT_64_P95_MS=" + p95);
        assertTrue(p95 < 800, "pgvector p95 exceeded the retrieval-stage budget: " + p95 + " ms");
    }

    private void verifyPostgresBackupAndRestoreDrill() throws Exception {
        assertExec(postgres.execInContainer("createdb", "-U", "modelrag", "modelrag_recovery"),
                "create isolated recovery database");
        try {
            assertExec(postgres.execInContainer("psql", "-U", "modelrag", "-d", "modelrag_recovery",
                    "-v", "ON_ERROR_STOP=1", "-c",
                    "CREATE TABLE recovery_marker(id integer primary key, value text not null);"
                            + " INSERT INTO recovery_marker VALUES (1, 'backup-restored');"),
                    "seed recovery fixture");
            assertExec(postgres.execInContainer("pg_dump", "-U", "modelrag", "-d", "modelrag_recovery",
                    "--format=custom", "--no-owner", "--file=/tmp/modelrag-recovery.dump"),
                    "create PostgreSQL custom-format backup");
            assertExec(postgres.execInContainer("psql", "-U", "modelrag", "-d", "modelrag_recovery",
                    "-v", "ON_ERROR_STOP=1", "-c", "DROP TABLE recovery_marker;"),
                    "remove the source table before restore");
            assertExec(postgres.execInContainer("pg_restore", "-U", "modelrag", "-d", "modelrag_recovery",
                    "--no-owner", "/tmp/modelrag-recovery.dump"), "restore PostgreSQL backup");
            var restored = postgres.execInContainer("psql", "-U", "modelrag", "-d", "modelrag_recovery",
                    "-tAc", "SELECT value FROM recovery_marker WHERE id=1");
            assertExec(restored, "verify restored data");
            assertEquals("backup-restored", restored.getStdout().trim());
        } finally {
            postgres.execInContainer("rm", "-f", "/tmp/modelrag-recovery.dump");
            postgres.execInContainer("dropdb", "-U", "modelrag", "--if-exists", "modelrag_recovery");
        }
    }

    private void assertExec(org.testcontainers.containers.Container.ExecResult result, String operation) {
        assertEquals(0, result.getExitCode(),
                () -> operation + " failed: " + result.getStderr() + result.getStdout());
    }

    private void verifyConcurrentDuplicateUploadIsAtomic(JdbcTemplate database, long datasetId) throws Exception {
        DocumentService documents = new DocumentService(new PostgresKnowledgeStore(database, (ignored, text) -> {
            float[] embedding = new float[1024];
            embedding[0] = 1;
            return embedding;
        }), objectStorage, event -> { }, 1_000, 5_000_000, 50L * 1024 * 1024);
        byte[] content = "concurrent upload must retain exactly one database fact and one object pair"
                .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
        java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            List<java.util.concurrent.Future<Object>> attempts = java.util.stream.IntStream.range(0, 2)
                    .mapToObj(index -> executor.submit(() -> {
                        ready.countDown();
                        start.await();
                        try {
                            return documents.upload(datasetId, "concurrent.txt", "text/plain", content.length,
                                    new java.io.ByteArrayInputStream(content));
                        } catch (BusinessException error) {
                            return error;
                        }
                    })).toList();
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
            start.countDown();
            List<Object> outcomes = new java.util.ArrayList<>();
            for (var attempt : attempts) outcomes.add(attempt.get(30, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(1, outcomes.stream().filter(com.modelrag.knowledge.model.Document.class::isInstance).count());
            assertEquals(1, outcomes.stream().filter(BusinessException.class::isInstance)
                    .map(BusinessException.class::cast)
                    .filter(error -> error.errorCode() == ErrorCode.DUPLICATE_DOCUMENT).count());
        }
        String hash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                .digest(content));
        assertEquals(1, database.queryForObject("""
                SELECT count(*) FROM kb_document
                WHERE dataset_id=? AND file_hash=? AND delete_time IS NULL
                """, Integer.class, datasetId, hash));
        var objects = s3.listObjectsV2(ListObjectsV2Request.builder().bucket("modelrag")
                .prefix("datasets/" + datasetId + "/documents/" + hash + "/uploads/").build()).contents();
        assertEquals(2, objects.size(), "the losing upload must clean only its private source/artifact pair");
    }

    private void verifyLongTermMemoryGovernance(org.springframework.jdbc.core.JdbcTemplate database, long datasetId) {
        database.update("INSERT INTO kb_user_account(user_id,display_name,password_hash,enabled) VALUES ('memory-a','Memory A','$argon2id$test',TRUE)");
        database.update("INSERT INTO kb_user_account(user_id,display_name,password_hash,enabled) VALUES ('memory-b','Memory B','$argon2id$test',TRUE)");
        database.update("INSERT INTO kb_memory_setting(user_id,enabled,retention_days) VALUES ('memory-a',TRUE,30)");
        database.update("INSERT INTO kb_memory_setting(user_id,enabled,retention_days) VALUES ('memory-b',TRUE,30)");
        EmbeddingService embedding = mock(EmbeddingService.class);
        float[] vector = new float[1024];
        vector[0] = 1;
        when(embedding.embed(anyLong(), anyString())).thenReturn(vector);
        LongTermMemoryService memories = new LongTermMemoryService(database, embedding);

        var pending = memories.upsert(new LongTermMemoryService.Memory(null, "memory-a", datasetId,
                "DATASET", "BUSINESS_FACT", "approval-days", "审批期限为 30 天",
                "PENDING_CONFIRMATION", .5, .8, null));
        assertEquals("PENDING_CONFIRMATION", pending.status());
        assertTrue(memories.retrieveRelevant("memory-a", datasetId, "审批期限", 5, false).isEmpty());
        var active = memories.confirm("memory-a", pending.id());
        assertEquals("ACTIVE", active.status());
        assertThrows(IllegalArgumentException.class, () -> memories.confirm("memory-a", pending.id()));
        assertThrows(IllegalArgumentException.class, () -> memories.pause("memory-b", pending.id()));
        assertEquals("PAUSED", memories.pause("memory-a", pending.id()).status());
        assertEquals("ACTIVE", memories.resume("memory-a", pending.id()).status());
        assertThrows(IllegalArgumentException.class, () -> memories.upsert(new LongTermMemoryService.Memory(
                null, "memory-a", null, "USER_GLOBAL", "BUSINESS_FACT", "leak", "不得跨库传播",
                "ACTIVE", .9, .9, null)));

        memories.upsert(new LongTermMemoryService.Memory(null, "memory-a", null,
                "USER_GLOBAL", "PREFERENCE", "answer-style", "回答保持简洁",
                "ACTIVE", .9, .9, null));
        assertEquals(2, memories.list("memory-a", null, true, 100).size());
        memories.clear("memory-a", null);
        assertTrue(memories.list("memory-a", null, true, 100).isEmpty());
        assertEquals(2, database.queryForObject(
                "SELECT count(*) FROM kb_user_memory_audit WHERE user_id='memory-a' AND action='CLEAR'",
                Integer.class));
    }

    private void verifyConversationJsonbPersistence(JdbcTemplate database, long datasetId) {
        long conversationId = conversationMemory.create("integration-admin", datasetId, "JSONB message fixture");
        conversationMemory.append("integration-admin", conversationId, "user", "How many leave days?",
                "[]", null, "rag", "fixture-ann");
        conversationMemory.append("integration-admin", conversationId, "assistant", "Five working days.",
                "[{\"chunkId\":1,\"score\":0.99}]", "trace-fixture", "rag", "fixture-ann");

        assertEquals(2, database.queryForObject(
                "SELECT message_count FROM kb_conversation WHERE id=?", Integer.class, conversationId));
        assertEquals(2, database.queryForObject(
                "SELECT count(*) FROM kb_message WHERE conversation_id=?", Integer.class, conversationId));
        assertEquals("jsonb", database.queryForObject(
                "SELECT pg_typeof(citations)::text FROM kb_message WHERE conversation_id=? LIMIT 1",
                String.class, conversationId));
    }

    private void verifyVersionedOutboxAliasAndDeferredDeletion(org.springframework.jdbc.core.JdbcTemplate database,
            long datasetId, long documentId, long firstChunkId) throws Exception {
        String es = "http://" + elasticsearch.getHost() + ":" + elasticsearch.getMappedPort(9200);
        HttpClient http = HttpClient.newHttpClient();
        outbox.append("UPSERT_CHUNK", datasetId, documentId, firstChunkId,
                chunkPayload(firstChunkId, datasetId, documentId, 1, "old searchable version"));
        outboxConsumer.deliverDueEvents();
        assertEquals("DONE", database.queryForObject(
                "SELECT status FROM kb_index_outbox WHERE document_id=? ORDER BY id DESC LIMIT 1",
                String.class, documentId));
        assertTrue(get(http, es + "/_alias/modelrag-chunks-active").contains("modelrag-chunks-v1"));

        long newChunkId = database.queryForObject("""
                INSERT INTO kb_chunk(document_id,dataset_id,chunk_index,content,embedding,version)
                VALUES (?,?,999,'new searchable version',CAST(? AS vector),7) RETURNING id
                """, Long.class, documentId, datasetId, unitVector(0));
        database.update("""
                UPDATE kb_document SET version=7,index_status='SEARCH_SYNCING',index_state='SEARCH_SYNCING'
                WHERE id=?
                """, documentId);
        outbox.append("UPSERT_CHUNK", datasetId, documentId, newChunkId,
                chunkPayload(newChunkId, datasetId, documentId, 7, "new searchable version"));
        assertTrue(get(http, es + "/_alias/modelrag-chunks-active").contains("modelrag-chunks-v1"),
                "old version must remain active while the replacement outbox event is pending");
        outboxConsumer.deliverDueEvents();
        String active = get(http, es + "/_alias/modelrag-chunks-active");
        assertTrue(active.contains("modelrag-chunks-v7"));
        assertTrue(active.contains("modelrag-chunks-v1"),
                "shared physical versions remain in the alias while other active documents still use them");
        assertEquals(7L, database.queryForObject(
                "SELECT active_index_version FROM kb_document WHERE id=?", Long.class, documentId));
        assertEquals("READY", database.queryForObject(
                "SELECT index_state FROM kb_document WHERE id=?", String.class, documentId));
        post(http, es + "/modelrag-chunks-active/_refresh", "");
        assertTrue(post(http, es + "/modelrag-chunks-active/_search",
                "{\"query\":{\"bool\":{\"filter\":[{\"term\":{\"documentId\":" + documentId
                        + "}},{\"term\":{\"version\":7}}]}}}")
                .contains("new searchable version"));

        String sourceKey = "integration/source-" + documentId + ".txt";
        String artifactKey = "integration/artifact-" + documentId + ".txt";
        putObject(sourceKey, "source");
        putObject(artifactKey, "artifact");
        database.update("UPDATE kb_document SET source_object_key=?,artifact_object_key=? WHERE id=?",
                sourceKey, artifactKey, documentId);
        deletions.deleteDocument(datasetId, documentId);
        assertTrue(Boolean.TRUE.equals(database.queryForObject(
                "SELECT delete_time IS NOT NULL FROM kb_document WHERE id=?", Boolean.class, documentId)));
        assertTrue(Boolean.TRUE.equals(database.queryForObject(
                "SELECT bool_and(delete_time IS NOT NULL) FROM kb_chunk WHERE document_id=?", Boolean.class, documentId)));
        assertObjectExists(sourceKey);
        assertObjectExists(artifactKey);

        outboxConsumer.deliverDueEvents();
        assertEquals("DONE", database.queryForObject("""
                SELECT status FROM kb_index_outbox
                WHERE document_id=? AND event_type='DELETE_DOCUMENT' ORDER BY id DESC LIMIT 1
                """, String.class, documentId));
        assertObjectMissing(sourceKey);
        assertObjectMissing(artifactKey);
        post(http, es + "/modelrag-chunks-active/_refresh", "");
        String deletedSearch = post(http, es + "/modelrag-chunks-active/_search",
                "{\"query\":{\"term\":{\"documentId\":" + documentId + "}}}");
        assertTrue(deletedSearch.contains("\"value\":0"));
    }

    private void putObject(String key, String value) {
        s3.putObject(PutObjectRequest.builder().bucket("modelrag").key(key).build(),
                RequestBody.fromString(value));
    }

    private void assertObjectExists(String key) {
        s3.headObject(HeadObjectRequest.builder().bucket("modelrag").key(key).build());
    }

    private void assertObjectMissing(String key) {
        S3Exception missing = assertThrows(S3Exception.class,
                () -> s3.headObject(HeadObjectRequest.builder().bucket("modelrag").key(key).build()));
        assertEquals(404, missing.statusCode());
    }

    private static String get(HttpClient http, String uri) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(uri)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "GET " + uri + " returned " + response.statusCode() + ": " + response.body());
        return response.body();
    }

    private static String post(HttpClient http, String uri, String body) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(uri))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
        assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                () -> "POST " + uri + " returned " + response.statusCode() + ": " + response.body());
        return response.body();
    }

    private static String chunkPayload(long chunkId, long datasetId, long documentId, long version, String content) {
        return "{\"chunkId\":" + chunkId + ",\"datasetId\":" + datasetId
                + ",\"documentId\":" + documentId + ",\"chunkIndex\":0,\"version\":" + version
                + ",\"indexType\":\"default\",\"content\":\"" + content
                + "\",\"titlePath\":\"fixture\",\"documentName\":\"fixture.txt\",\"metadata\":{}}";
    }

    private static String unitVector(int axis) {
        StringBuilder value = new StringBuilder("[");
        for (int index = 0; index < 1024; index++) {
            if (index > 0) value.append(',');
            value.append(index == axis ? '1' : '0');
        }
        return value.append(']').toString();
    }
}
