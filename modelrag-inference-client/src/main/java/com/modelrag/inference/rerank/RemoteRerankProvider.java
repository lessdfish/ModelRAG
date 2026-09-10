package com.modelrag.inference.rerank;

import com.modelrag.inference.client.AiServiceClient;
import com.modelrag.inference.client.AiServiceException;
import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Calls the stateless Python reranker and validates its identity-preserving response. */
@Service
public class RemoteRerankProvider implements RerankComputeProvider {
    private static final int MAX_CANDIDATES = 100;
    private static final int MAX_QUERY_CHARS = 100_000;
    private static final int MAX_DOCUMENT_CHARS = 100_000;
    private final AiServiceClient client;

    public RemoteRerankProvider(AiServiceClient client) {
        this.client = client;
    }

    @Override
    public List<RerankScore> rerank(String model, String query, List<RerankDocument> documents, Duration timeout) {
        validateRequest(model, query, documents);
        Duration budget = timeout == null ? Duration.ofSeconds(30) : timeout;
        if (budget.isZero() || budget.isNegative()) {
            throw new AiServiceException("rerank timeout budget is invalid", 408, false);
        }
        RerankResponse response = client.postJson("rerank", "/v1/rerank",
                new RerankRequest(model, query, documents), RerankResponse.class, budget);
        return validateResponse(response, documents.stream().map(RerankDocument::id).collect(java.util.stream.Collectors.toSet()));
    }

    private void validateRequest(String model, String query, List<RerankDocument> documents) {
        if (model == null || model.isBlank()) throw invalid("rerank model is required");
        if (query == null || query.isBlank() || query.length() > MAX_QUERY_CHARS) {
            throw invalid("rerank query is invalid");
        }
        if (documents == null || documents.isEmpty() || documents.size() > MAX_CANDIDATES) {
            throw invalid("rerank candidate count is invalid");
        }
        Set<String> ids = new HashSet<>();
        for (RerankDocument document : documents) {
            if (document == null || !ids.add(document.id()) || document.text().length() > MAX_DOCUMENT_CHARS) {
                throw invalid("rerank candidate is invalid");
            }
        }
    }

    private List<RerankScore> validateResponse(RerankResponse response, Set<String> expectedIds) {
        if (response == null || response.scores().size() != expectedIds.size()) {
            throw invalid("rerank response shape is invalid");
        }
        Set<String> ids = new HashSet<>();
        for (RerankScore score : response.scores()) {
            if (score == null || score.id() == null || !expectedIds.contains(score.id())
                    || !ids.add(score.id()) || !Double.isFinite(score.score())) {
                throw invalid("rerank response score is invalid");
            }
        }
        return response.scores();
    }

    private AiServiceException invalid(String message) {
        return new AiServiceException(message, 502, false);
    }
}
