package com.modelrag.common.sse;

import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Owns SSE emitter lifecycles. Callers address a stable stream key such as document:{id}. */
@Service
public class SseEmitterService {
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int REPLAY_LIMIT = 50;
    private static final int MAX_CONNECTIONS = 1000;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final Cache<String, Deque<SseEvent>> replay = Caffeine.newBuilder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build();
    private final AtomicInteger connections = new AtomicInteger();

    public SseEmitter subscribe(String streamKey) {
        if (connections.incrementAndGet() > MAX_CONNECTIONS) { connections.decrementAndGet(); throw new BusinessException(ErrorCode.RATE_LIMITED, "SSE 连接数已达上限"); }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(streamKey, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(streamKey, emitter));
        emitter.onTimeout(() -> { emitter.complete(); remove(streamKey, emitter); });
        Deque<SseEvent> events = replay.getIfPresent(streamKey);
        if (events != null) for (SseEvent event : new ArrayList<>(events)) {
            try { emitter.send(SseEmitter.event().name(event.type()).data(event)); }
            catch (IOException ex) { emitter.completeWithError(ex); remove(streamKey, emitter); break; }
        }
        return emitter;
    }

    public void publish(String streamKey, SseEvent event) {
        Deque<SseEvent> events = replay.get(streamKey, ignored -> new ConcurrentLinkedDeque<>());
        events.addLast(event);
        while (events.size() > REPLAY_LIMIT) events.pollFirst();
        for (SseEmitter emitter : emitters.getOrDefault(streamKey, List.of())) {
            try { emitter.send(SseEmitter.event().name(event.type()).data(event)); }
            catch (IOException ex) { emitter.completeWithError(ex); remove(streamKey, emitter); }
        }
    }

    public void heartbeat() { emitters.keySet().forEach(key -> publish(key, new SseEvent("HEARTBEAT", "heartbeat", Map.of()))); }

    public void complete(String streamKey) {
        List<SseEmitter> current = List.copyOf(emitters.getOrDefault(streamKey, List.of()));
        for (SseEmitter emitter : current) {
            emitter.complete();
            remove(streamKey, emitter);
        }
    }

    private void remove(String key, SseEmitter emitter) {
        List<SseEmitter> current = emitters.get(key);
        if (current != null && current.remove(emitter)) { connections.decrementAndGet(); if (current.isEmpty()) emitters.remove(key, current); }
    }
}
