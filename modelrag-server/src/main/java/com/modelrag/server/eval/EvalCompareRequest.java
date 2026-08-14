package com.modelrag.server.eval;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.List;

public record EvalCompareRequest(@Valid List<EvalItem> items, List<@Min(1) @Max(20) Integer> topKs) {
    public List<EvalItem> safeItems() { return items == null ? List.of() : List.copyOf(items); }
    public List<Integer> safeTopKs() { return topKs == null || topKs.isEmpty() ? List.of(3, 5, 8, 10) : List.copyOf(topKs); }
}
