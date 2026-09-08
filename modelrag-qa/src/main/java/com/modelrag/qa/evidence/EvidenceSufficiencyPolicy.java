package com.modelrag.qa.evidence;

import java.util.List;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/** Deterministic evidence sufficiency; it never calls an LLM. */
@Service
public class EvidenceSufficiencyPolicy {
    private static final Pattern MULTI_PART = Pattern.compile("分别|各自|对比|比较|对照|A和B");
    private final double minimumScore;

    public EvidenceSufficiencyPolicy() {
        this(0.0);
    }

    @Autowired
    public EvidenceSufficiencyPolicy(
            @Value("${modelrag.qa.v2.min-evidence-score:0}") double minimumScore) {
        if (Double.isNaN(minimumScore) || Double.isInfinite(minimumScore)) {
            throw new IllegalArgumentException("minimum evidence score is invalid");
        }
        this.minimumScore = minimumScore;
    }

    public EvidenceSufficiency evaluate(String query, List<Evidence> evidence) {
        List<Evidence> values = evidence == null ? List.of() : evidence;
        List<Evidence> primary = values.stream().filter(Evidence::primary).toList();
        if (primary.isEmpty()) return new EvidenceSufficiency(false, 0, "no-primary-evidence", 0);
        double top = primary.stream().mapToDouble(Evidence::score).max().orElse(0);
        if (top <= minimumScore) {
            return new EvidenceSufficiency(false, confidence(top), "low-evidence-score", 0);
        }
        int required = requiresCoverage(query) ? 2 : 1;
        long sources = primary.stream().map(Evidence::sourceKey).distinct().count();
        double coverage = Math.min(1, (double) sources / required);
        if (sources < required) {
            return new EvidenceSufficiency(false, confidence(top), "insufficient-multipart-coverage", coverage);
        }
        return new EvidenceSufficiency(true, confidence(top), "active-evidence", coverage);
    }

    private boolean requiresCoverage(String query) {
        return query != null && MULTI_PART.matcher(query).find();
    }

    private double confidence(double score) {
        return Math.max(0, Math.min(1, score));
    }
}
