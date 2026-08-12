package com.modelrag.indexing.outbox;
import com.modelrag.common.outbox.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;
/** Development implementation; production adapter persists identical events to kb_index_outbox. */
@Service @Profile("!postgres") public class InMemoryIndexOutbox implements IndexOutbox { private final AtomicLong ids=new AtomicLong(); private final Map<Long,IndexOutboxEvent> events=new ConcurrentHashMap<>(); public IndexOutboxEvent append(String type,long dataset,long document,long chunk,String payload){var event=new IndexOutboxEvent(ids.incrementAndGet(),type,dataset,document,chunk,payload,"PENDING",0,Instant.EPOCH,null);events.put(event.id(),event);return event;} public List<IndexOutboxEvent> due(){Instant now=Instant.now();return events.values().stream().filter(e->"PENDING".equals(e.status())&&!e.nextRetryAt().isAfter(now)).sorted(Comparator.comparingLong(IndexOutboxEvent::id)).toList();} public void save(IndexOutboxEvent event){events.put(event.id(),event);}public int requeueDataset(long datasetId){List<IndexOutboxEvent> selected=events.values().stream().filter(e->e.datasetId()==datasetId).toList();selected.forEach(e->events.put(e.id(),new IndexOutboxEvent(e.id(),e.eventType(),e.datasetId(),e.documentId(),e.chunkId(),e.payload(),"PENDING",0,Instant.EPOCH,null)));return selected.size();} }
