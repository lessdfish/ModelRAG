package com.modelrag.common.sse;

import com.modelrag.common.dto.SseEvent;
import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Owns live HTTP emitters; replay state is stored in the injected short-lived store. */
@Service
public class SseEmitterService {
    private static final Logger LOG = LoggerFactory.getLogger(SseEmitterService.class);
    private static final long TIMEOUT_MS = 30 * 60 * 1000L;
    private static final int MAX_CONNECTIONS = 1000;
    private final SseReplayStore replay;
    private final Map<String, List<SseEmitter>> emitters = new ConcurrentHashMap<>();
    private final AtomicInteger connections = new AtomicInteger();

    public SseEmitterService(SseReplayStore replay) {
        this.replay = replay;
    }

    public SseEmitter subscribe(String streamKey) {
        if (connections.incrementAndGet() > MAX_CONNECTIONS) {
            connections.decrementAndGet();
            throw new BusinessException(ErrorCode.RATE_LIMITED, "SSE 连接数已达上限");
        }
        SseEmitter emitter = new SseEmitter(TIMEOUT_MS);
        emitters.computeIfAbsent(streamKey, ignored -> new CopyOnWriteArrayList<>()).add(emitter);
        emitter.onCompletion(() -> remove(streamKey, emitter));
        emitter.onTimeout(() -> {
            emitter.complete();
            remove(streamKey, emitter);
        });
        try {
            for (SseEvent event : replay.replay(streamKey)) {
                emitter.send(SseEmitter.event().name(event.type()).data(event));
            }
        } catch (RuntimeException | IOException error) {
            LOG.warn("SSE 回放不可用，继续使用实时连接 streamKey={}", streamKey, error);
        }
        return emitter;
    }

    public void publish(String streamKey, SseEvent event) {
        try {
            replay.append(streamKey, event);
        } catch (RuntimeException error) {
            LOG.warn("SSE 回放暂不可用，实时事件继续发送 streamKey={}", streamKey, error);
        }
        for (SseEmitter emitter : emitters.getOrDefault(streamKey, List.of())) {
            try {
                emitter.send(SseEmitter.event().name(event.type()).data(event));
            } catch (IOException error) {
                emitter.completeWithError(error);
                remove(streamKey, emitter);
            }
        }
    }

    public void heartbeat() {
        new ArrayList<>(emitters.keySet()).forEach(key -> publish(key, new SseEvent("HEARTBEAT", "heartbeat", Map.of())));
    }

    public void complete(String streamKey) {
        List<SseEmitter> current = List.copyOf(emitters.getOrDefault(streamKey, List.of()));
        for (SseEmitter emitter : current) {
            emitter.complete();
            remove(streamKey, emitter);
        }
    }

    private void remove(String key, SseEmitter emitter) {
        List<SseEmitter> current = emitters.get(key);
        if (current != null && current.remove(emitter)) {
            connections.decrementAndGet();
            if (current.isEmpty()) emitters.remove(key, current);
        }
    }
}
