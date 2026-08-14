package com.modelrag.server.health;
import org.springframework.boot.health.contributor.*; import org.springframework.context.annotation.Profile; import org.springframework.stereotype.Component;
@Component("mockModel") @Profile("test") public class ModelRagHealthIndicator implements HealthIndicator {public Health health(){return Health.up().withDetail("provider","deterministic-local-mock").build();}}
