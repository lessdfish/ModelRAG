package com.modelrag.server.health;
import org.springframework.boot.actuate.health.*; import org.springframework.stereotype.Component;
@Component("mockModel") public class ModelRagHealthIndicator implements HealthIndicator {public Health health(){return Health.up().withDetail("provider","deterministic-local-mock").build();}}
