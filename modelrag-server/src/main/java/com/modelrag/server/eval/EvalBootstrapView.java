package com.modelrag.server.eval;

import java.util.List;

public record EvalBootstrapView(int created, List<EvalItem> items) {
}
