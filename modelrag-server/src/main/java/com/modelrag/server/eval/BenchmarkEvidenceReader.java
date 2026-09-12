package com.modelrag.server.eval;

public interface BenchmarkEvidenceReader {
    BenchmarkEvidence read(String benchmarkIdentity);
}
