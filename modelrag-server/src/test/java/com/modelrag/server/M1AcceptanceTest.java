package com.modelrag.server;

import com.modelrag.indexing.outbox.ElasticsearchOutboxConsumer;
import com.modelrag.indexing.outbox.InMemoryIndexOutbox;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class M1AcceptanceTest {
    @Test void failedOutboxEventCanBeRequeuedAndDelivered() throws Exception {
        InMemoryIndexOutbox outbox=new InMemoryIndexOutbox(); AtomicInteger requests=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress(0),0);
        server.createContext("/modelrag-chunks/_doc/3",exchange->{int status=requests.incrementAndGet()==1?500:201;exchange.sendResponseHeaders(status,-1);exchange.close();});server.start();
        try {outbox.append("UPSERT_CHUNK",1,2,3,"{}");ElasticsearchOutboxConsumer consumer=new ElasticsearchOutboxConsumer(outbox,"http://127.0.0.1:"+server.getAddress().getPort());consumer.deliverDueEvents();assertEquals(1,requests.get());assertEquals(1,outbox.requeueDataset(1));consumer.deliverDueEvents();assertEquals(2,requests.get());assertTrue(outbox.due().isEmpty());} finally {server.stop(0);}
    }
}
