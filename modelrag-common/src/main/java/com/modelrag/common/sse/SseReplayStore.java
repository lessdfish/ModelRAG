package com.modelrag.common.sse;

import com.modelrag.common.dto.SseEvent;
import java.util.List;

/** Short-lived replay storage for SSE events; production implementations use Redis. */
public interface SseReplayStore {
    void append(String streamKey, SseEvent event);

    List<SseEvent> replay(String streamKey);
}
