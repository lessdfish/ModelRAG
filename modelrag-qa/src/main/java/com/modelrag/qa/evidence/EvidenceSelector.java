package com.modelrag.qa.evidence;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Applies bounded source identity deduplication and assigns stable execution-local IDs. */
@Service
public class EvidenceSelector {
    private final int maxEvidence;

    public EvidenceSelector() {
        this(16);
    }

    @Autowired
    public EvidenceSelector(@Value("${modelrag.qa.v2.max-expanded-evidence:16}") int maxEvidence) {
        this.maxEvidence = Math.max(1, Math.min(EvidenceSet.MAX_EVIDENCE, maxEvidence));
    }

    public List<Evidence> select(List<Evidence> candidates) {
        if (candidates == null || candidates.isEmpty()) return List.of();
        Map<String, Evidence> unique = new LinkedHashMap<>();
        for (Evidence candidate : candidates) {
            if (candidate == null) continue;
            String key = candidate.sourceKey();
            Evidence old = unique.get(key);
            if (old == null || wins(candidate, old)) unique.put(key, candidate);
        }
        List<Evidence> ordered = new ArrayList<>(unique.values());
        if (ordered.size() > maxEvidence) ordered = new ArrayList<>(ordered.subList(0, maxEvidence));
        List<Evidence> result = new ArrayList<>(ordered.size());
        for (int index = 0; index < ordered.size(); index++) {
            result.add(ordered.get(index).withEvidenceId("E" + (index + 1)));
        }
        return List.copyOf(result);
    }

    private boolean wins(Evidence candidate, Evidence old) {
        if (candidate.primary() != old.primary()) return candidate.primary();
        return candidate.primary() && candidate.score() > old.score();
    }
}
