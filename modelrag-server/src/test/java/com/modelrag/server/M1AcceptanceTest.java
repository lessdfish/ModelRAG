package com.modelrag.server;

import com.modelrag.indexing.outbox.ElasticsearchOutboxConsumer;
import com.modelrag.indexing.outbox.InMemoryIndexOutbox;
import com.modelrag.knowledge.service.KnowledgeStore;
import com.modelrag.knowledge.service.ObjectStorageService;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class M1AcceptanceTest {
    @Test void failedOutboxEventCanBeRequeuedAndDelivered() throws Exception {
        InMemoryIndexOutbox outbox=new InMemoryIndexOutbox(); AtomicInteger requests=new AtomicInteger();
        AtomicReference<String> aliasBody=new AtomicReference<>("");
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/modelrag-chunks-v1", exchange -> { exchange.sendResponseHeaders("HEAD".equals(exchange.getRequestMethod()) ? 200 : 201, -1); exchange.close(); });
        server.createContext("/modelrag-chunks-active", exchange -> { exchange.sendResponseHeaders("HEAD".equals(exchange.getRequestMethod()) ? 404 : 404, -1); exchange.close(); });
        server.createContext("/_aliases", exchange -> { aliasBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)); exchange.sendResponseHeaders(200, -1); exchange.close(); });
        server.createContext("/modelrag-chunks-v1/_doc/3",exchange->{int status=requests.incrementAndGet()==1?500:201;exchange.sendResponseHeaders(status,-1);exchange.close();});server.start();
        try {outbox.append("UPSERT_CHUNK",1,2,3,"{}");ElasticsearchOutboxConsumer consumer=consumer(outbox,"http://127.0.0.1:"+server.getAddress().getPort(),24);consumer.deliverDueEvents();assertEquals(1,requests.get());assertEquals(1,outbox.requeueDataset(1));consumer.deliverDueEvents();assertEquals(2,requests.get());assertTrue(outbox.due().isEmpty());assertTrue(aliasBody.get().contains("modelrag-chunks-v1"));assertTrue(aliasBody.get().contains("modelrag-chunks-active"));} finally {server.stop(0);}
    }


    @Test void retiredIndexCleanupKeepsAliasAndDeletesOnlyExpiredInactiveIndex() throws Exception {
        InMemoryIndexOutbox outbox=new InMemoryIndexOutbox(); AtomicInteger activeDeletes=new AtomicInteger(); AtomicInteger retiredDeletes=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/_alias/modelrag-chunks-active", exchange -> { byte[] body="{\"modelrag-chunks-v2\":{\"aliases\":{\"modelrag-chunks-active\":{}}}}".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close(); });
        server.createContext("/_cat/indices/modelrag-chunks-v*", exchange -> { byte[] body="[{\"index\":\"modelrag-chunks-v1\",\"creation.date\":\"1\"},{\"index\":\"modelrag-chunks-v2\",\"creation.date\":\"1\"}]".getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200,body.length); exchange.getResponseBody().write(body); exchange.close(); });
        server.createContext("/modelrag-chunks-v1", exchange -> {retiredDeletes.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.createContext("/modelrag-chunks-v2", exchange -> {activeDeletes.incrementAndGet();exchange.sendResponseHeaders(200,-1);exchange.close();});
        server.start();
        try {consumer(outbox,"http://127.0.0.1:"+server.getAddress().getPort(),1).cleanupRetiredIndexes();assertEquals(1,retiredDeletes.get());assertEquals(0,activeDeletes.get());} finally {server.stop(0);}
    }

    private ElasticsearchOutboxConsumer consumer(InMemoryIndexOutbox outbox, String endpoint, long retentionHours) {
        KnowledgeStore knowledge = mock(KnowledgeStore.class);
        when(knowledge.allActiveIndexVersions()).thenReturn(java.util.Map.of());
        return new ElasticsearchOutboxConsumer(outbox, knowledge, mock(ObjectStorageService.class), endpoint, retentionHours);
    }
}
