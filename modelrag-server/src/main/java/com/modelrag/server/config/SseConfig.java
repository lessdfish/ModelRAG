package com.modelrag.server.config;
import com.modelrag.common.sse.SseEmitterService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
@Component public class SseConfig {private final SseEmitterService sse;public SseConfig(SseEmitterService s){this.sse=s;}@Scheduled(fixedDelay=30000)public void heartbeat(){sse.heartbeat();}}
