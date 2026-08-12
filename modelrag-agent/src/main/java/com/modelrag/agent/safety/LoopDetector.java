package com.modelrag.agent.safety;
import java.util.*; import org.springframework.stereotype.Component;
/** Exact tool+parameter fingerprint is the deterministic guard used before an action is retried. */
@Component public class LoopDetector {public boolean detect(List<String> actions){if(actions.size()<2)return false;String last=actions.get(actions.size()-1);return actions.subList(0,actions.size()-1).stream().anyMatch(last::equals);}}
