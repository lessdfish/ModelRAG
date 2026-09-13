package com.modelrag.server.eval;

/** Evidence emitted only after the PostgreSQL-backed ACL isolation test passes. */
public record AclIsolationEvidence(String status, String gitCommit, String testName,
        String executedAt, String reason) {
    public AclIsolationEvidence {
        status = status == null ? "NOT_RUN" : status;
        gitCommit = gitCommit == null ? "unknown" : gitCommit;
        testName = testName == null ? "" : testName;
        executedAt = executedAt == null ? "" : executedAt;
        reason = reason == null ? "" : reason;
    }

    public static AclIsolationEvidence notRun(String reason) {
        return new AclIsolationEvidence("NOT_RUN", "unknown", "", "", reason);
    }

    public boolean passed() {
        return "PASSED".equals(status) && !testName.isBlank() && !executedAt.isBlank();
    }
}
