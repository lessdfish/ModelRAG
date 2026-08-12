package com.modelrag.server.health;
import com.modelrag.server.model.*; import org.springframework.boot.actuate.health.*; import org.springframework.stereotype.Component;
@Component("llm") public class LlmHealthIndicator implements HealthIndicator {private final ModelRouter router;public LlmHealthIndicator(ModelRouter router){this.router=router;}public Health health(){try{String provider=router.selectedClientName(ModelType.CHAT);return Health.up().withDetail("provider",provider).build();}catch(Exception e){return Health.down(e).build();}}}
