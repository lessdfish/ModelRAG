package com.modelrag.common.rate;

import com.modelrag.common.exception.BusinessException;
import com.modelrag.common.exception.ErrorCode;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

/** Fixed one-minute request window per knowledge base. */
@Service
public class DatasetRateLimiter {
    private static final int LIMIT=100; private static final long WINDOW_MS=60_000L;
    private final ConcurrentHashMap<Long,Window> windows=new ConcurrentHashMap<>();
    public void check(long datasetId){windows.compute(datasetId,(ignored,old)->{long now=System.currentTimeMillis();Window current=old==null||now-old.startedAt>=WINDOW_MS?new Window(now,0):old;if(current.requests>=LIMIT)throw new BusinessException(ErrorCode.RATE_LIMITED,"知识库请求过于频繁，请稍后再试");return new Window(current.startedAt,current.requests+1);});}
    private record Window(long startedAt,int requests){}
}
