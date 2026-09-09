package com.modelrag.agent.policy;

import com.modelrag.agent.retrieval.RetrievalActionName;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Deterministic validation boundary between model output and retrieval execution. */
@Service
public class AgentDecisionValidator {
    private static final int MAX_QUERY_CHARS = 2_000;
    private static final int MAX_SEARCH_LIMIT = 20;

    public AgentDecision validate(AgentDecision decision, AgentPolicyInput input) {
        if (decision == null || input == null) throw new IllegalArgumentException("decision/input is required");
        if (decision.type() == AgentDecisionType.FINISH) return AgentDecision.finish();
        if (decision.type() != AgentDecisionType.ACTION || decision.action() == null
                || decision.action() == RetrievalActionName.FINISH) {
            throw new IllegalArgumentException("unsupported agent decision");
        }
        if (input.remainingSteps() <= 0) throw new IllegalArgumentException("step budget is exhausted");
        Map<String, Object> args = decision.arguments();
        Set<String> allowed = allowedArguments(decision.action());
        if (!args.keySet().stream().allMatch(allowed::contains)) {
            throw new IllegalArgumentException("unknown retrieval action argument");
        }
        switch (decision.action()) {
            case SEARCH_KNOWLEDGE -> {
                query(args, "query");
                positiveBoundedInteger(args, "limit", MAX_SEARCH_LIMIT);
                if (input.remainingSearchActions() <= 0) throw new IllegalArgumentException("search budget is exhausted");
            }
            case FIND_IN_DOCUMENT -> {
                long documentId = positiveLong(args, "documentId");
                if (!input.observedDocumentIds().contains(documentId)) {
                    throw new IllegalArgumentException("documentId was not observed");
                }
                query(args, "query");
                positiveBoundedInteger(args, "limit", MAX_SEARCH_LIMIT);
                if (input.remainingSearchActions() <= 0) throw new IllegalArgumentException("search budget is exhausted");
            }
            case OPEN_NODE, READ_PARENT -> {
                long nodeId = positiveLong(args, "nodeId");
                requireObservedNode(input, nodeId);
                if (input.remainingNavigationActions() <= 0) throw new IllegalArgumentException("navigation budget is exhausted");
            }
            case READ_NEIGHBORS -> {
                long nodeId = positiveLong(args, "nodeId");
                requireObservedNode(input, nodeId);
                positiveBoundedInteger(args, "radius", 2);
                if (input.remainingNavigationActions() <= 0) throw new IllegalArgumentException("navigation budget is exhausted");
            }
            case READ_CHILDREN -> {
                long nodeId = positiveLong(args, "nodeId");
                requireObservedNode(input, nodeId);
                nonNegativeBoundedInteger(args, "offset", 1_000);
                positiveBoundedInteger(args, "limit", 20);
                if (input.remainingNavigationActions() <= 0) throw new IllegalArgumentException("navigation budget is exhausted");
            }
            case FOLLOW_REFERENCES -> {
                long nodeId = positiveLong(args, "nodeId");
                requireObservedNode(input, nodeId);
                positiveBoundedInteger(args, "limit", 10);
                if (input.remainingNavigationActions() <= 0) throw new IllegalArgumentException("navigation budget is exhausted");
            }
            case FINISH -> throw new IllegalArgumentException("FINISH is not an action");
        }
        return decision;
    }

    public Set<String> allowedArguments(RetrievalActionName action) {
        return switch (action) {
            case SEARCH_KNOWLEDGE -> Set.of("query", "limit");
            case OPEN_NODE, READ_PARENT -> Set.of("nodeId");
            case FIND_IN_DOCUMENT -> Set.of("documentId", "query", "limit");
            case READ_NEIGHBORS -> Set.of("nodeId", "radius");
            case READ_CHILDREN -> Set.of("nodeId", "offset", "limit");
            case FOLLOW_REFERENCES -> Set.of("nodeId", "limit");
            case FINISH -> Set.of();
        };
    }

    private void requireObservedNode(AgentPolicyInput input, long nodeId) {
        if (!input.observedNodeIds().contains(nodeId)) throw new IllegalArgumentException("nodeId was not observed");
    }

    private String query(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof String text) || text.isBlank() || text.length() > MAX_QUERY_CHARS) {
            throw new IllegalArgumentException(name + " must be a bounded string");
        }
        return text.trim();
    }

    private int positiveBoundedInteger(Map<String, Object> args, String name, int max) {
        int value = integer(args, name, 1);
        if (value < 1 || value > max) throw new IllegalArgumentException(name + " is out of bounds");
        return value;
    }

    private int nonNegativeInteger(Map<String, Object> args, String name) {
        int value = integer(args, name, 0);
        if (value < 0) throw new IllegalArgumentException(name + " must not be negative");
        return value;
    }

    private int nonNegativeBoundedInteger(Map<String, Object> args, String name, int max) {
        int value = nonNegativeInteger(args, name);
        if (value > max) throw new IllegalArgumentException(name + " is out of bounds");
        return value;
    }

    private int integer(Map<String, Object> args, String name, int defaultValue) {
        Object value = args.get(name);
        if (value == null) return defaultValue;
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long result = number.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(name + " is out of range");
        }
        return (int) result;
    }

    private long positiveLong(Map<String, Object> args, String name) {
        Object value = args.get(name);
        if (!(value instanceof Number number) || number.doubleValue() != Math.rint(number.doubleValue())) {
            throw new IllegalArgumentException(name + " must be an integer");
        }
        long result = number.longValue();
        if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }
}
