import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace


MODULE_PATH = Path(__file__).with_name("benchmark.py")
SPEC = importlib.util.spec_from_file_location("retrieval_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(benchmark)


class Cursor:
    def __init__(self, values):
        self.values = iter(values)

    def __enter__(self): return self
    def __exit__(self, *_): return False
    def execute(self, *_): return None
    def fetchone(self): return next(self.values)


class Connection:
    def __init__(self, values): self.values = values
    def __enter__(self): return self
    def __exit__(self, *_): return False
    def cursor(self): return Cursor(self.values)


def manifest():
    return {"fixtureIdentity": "fixture-a", "activeRetrievalUnits": 1_000_000,
            "activeDocuments": 20_001, "activeBuildCount": 20_001,
            "staleRetrievalUnits": 10_000, "vectorDimension": 1024,
            "activeBuildSentinel": "G11_ACTIVE_BUILD_SENTINEL_20001"}


def install_psycopg(monkeypatch, values):
    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda *_args, **_kwargs: Connection(values)))


def test_manifest_million_but_postgres_smaller_is_not_runnable(monkeypatch):
    values = [(999_999, 10_000), (20_001,), (20_001,), (1_009_999, 1024, 1024, "qwen3-v1"), (1,)]
    install_psycopg(monkeypatch, values)
    monkeypatch.setattr(benchmark, "http_json", lambda *_: (200, {"count": 1_010_000}, 0))

    _, errors = benchmark.preflight("postgresql://fixture", "http://es", 1, manifest(), 1)

    assert any("activeRetrievalUnits" in error for error in errors)


def test_postgres_and_elasticsearch_fixture_identity_mismatch_is_not_runnable(monkeypatch):
    values = [(1_000_000, 10_000), (20_001,), (20_001,), (1_010_000, 1024, 1024, "qwen3-v1"), (2,)]
    install_psycopg(monkeypatch, values)
    monkeypatch.setattr(benchmark, "http_json", lambda *_: (200, {"count": 0}, 0))

    _, errors = benchmark.preflight("postgresql://fixture", "http://es", 1, manifest(), 1)

    assert any("fixtureIdentity" in error for error in errors)
    assert any("Elasticsearch fixture count" in error for error in errors)


def test_high_active_build_workload_queries_real_sentinel_and_captures_server_java(monkeypatch):
    seen = {}

    def response(_target, body, _timeout, _headers):
        seen.update(body)
        return 200, {"candidateCount": 1, "boundedResults": True, "evidenceStatus": "VALID",
                     "sentinelRecall": True,
                     "serverJavaVersion": "21.0.8"}, 0.01

    monkeypatch.setattr(benchmark, "http_json", response)
    workload = {"name": "high-active-build-count", "mode": "lexical",
                "querySource": "activeBuildSentinel", "activeBuildOverflow": True}

    result = benchmark.request_once("http://server", workload, 0, 1, manifest(), 1)

    assert seen["query"] == "G11_ACTIVE_BUILD_SENTINEL_20001"
    assert result["serverJavaVersion"] == "21.0.8"
    assert result["sentinelRecall"] is True
    assert result["evidenceStatus"] == "VALID"
    assert "aclLeakage" not in result


def test_runner_counts_no_evidence_separately_from_invalid_evidence(monkeypatch):
    responses = iter([
        {"error": False, "degraded": False, "timeout": False, "latencyMs": 1,
         "stageLatencyMs": {}, "staleCandidateCount": 0, "activeBuildTruncated": False,
         "sentinelRecall": False, "boundedResults": True, "evidenceStatus": "NO_EVIDENCE",
         "serverJavaVersion": "21.0.8"},
        {"error": False, "degraded": False, "timeout": False, "latencyMs": 1,
         "stageLatencyMs": {}, "staleCandidateCount": 1, "activeBuildTruncated": False,
         "sentinelRecall": False, "boundedResults": True, "evidenceStatus": "INVALID",
         "serverJavaVersion": "21.0.8"},
    ])
    monkeypatch.setattr(benchmark, "request_once", lambda *_: next(responses))
    options = SimpleNamespace(requests_per_workload=2, warmup_requests=0, dataset_id=1, timeout_seconds=1)

    result = benchmark.run_workload("http://server", {"name": "semantic-only", "mode": "semantic"},
                                    1, options, manifest())

    assert result["noEvidenceCount"] == 1
    assert result["invalidEvidenceCount"] == 1


def report_result(**overrides):
    result = {"workload": "high-active-build-count", "requests": 1, "p50Ms": 1,
              "p95Ms": 1, "p99Ms": 1, "errors": 0, "degraded": 0, "timeouts": 0,
              "staleCandidateCount": 0, "activeBuildTruncationCount": 0,
              "sentinelRecallFailures": 0, "boundedResultFailures": 0,
              "noEvidenceCount": 0, "invalidEvidenceCount": 0,
              "serverJavaVersions": ["21.0.8"]}
    result.update(overrides)
    return result


def test_smoke_marks_active_build_truncation_not_applicable(monkeypatch):
    smoke = manifest() | {"activeRetrievalUnits": 20_000, "activeDocuments": 2_000,
                          "activeBuildCount": 2_000, "staleRetrievalUnits": 200}
    monkeypatch.setattr(benchmark, "git_commit", lambda: "commit")

    report = benchmark.build_report(SimpleNamespace(mode="smoke"), smoke,
                                    [{"name": "high-active-build-count"}], [report_result()], {}, {})

    assert report["correctness"]["sentinelRecall"] is True
    assert report["correctness"]["activeBuildTruncationStatus"] == "NOT_APPLICABLE"


def test_full_overflow_reports_pass_only_with_sentinel_and_no_truncation(monkeypatch):
    monkeypatch.setattr(benchmark, "git_commit", lambda: "commit")

    report = benchmark.build_report(SimpleNamespace(mode="full"), manifest(),
                                    [{"name": "high-active-build-count"}], [report_result()], {}, {})

    assert report["correctness"]["sentinelRecall"] is True
    assert report["correctness"]["activeBuildTruncationStatus"] == "PASS"


def test_full_benchmark_rejects_missing_or_inconsistent_server_java_versions():
    assert benchmark.full_server_java_error([
        {"missingServerJavaVersionCount": 1, "serverJavaVersions": ["21.0.8"]}
    ]) is not None
    assert benchmark.full_server_java_error([
        {"missingServerJavaVersionCount": 0, "serverJavaVersions": ["21.0.8"]},
        {"missingServerJavaVersionCount": 0, "serverJavaVersions": ["21.0.9"]},
    ]) is not None
    assert benchmark.full_server_java_error([
        {"missingServerJavaVersionCount": 0, "serverJavaVersions": ["21.0.8"]}
    ]) is None
    for invalid in ("17.0.12", "unknown", "unavailable", ""):
        result = [{"missingServerJavaVersionCount": 0, "serverJavaVersions": [invalid] if invalid else []}]
        assert benchmark.full_server_java_error(result) is not None


def test_request_captures_performance_stages_and_channel_timeout_component(monkeypatch):
    monkeypatch.setattr(benchmark, "http_json", lambda *_: (200, {
        "timeout": True,
        "timeoutComponents": ["SEMANTIC_TIMEOUT"],
        "degradedComponents": ["SEMANTIC_TIMEOUT"],
        "timeoutStatus": "TIMED_OUT_AT_CHANNEL_BUDGET",
        "performanceLatencyMs": {"semantic.queueWaitMs": 12.5},
        "performanceAttributes": {"semantic.embeddingCacheOutcome": "MISS_REMOTE"},
        "evidenceStatus": "NO_EVIDENCE",
        "boundedResults": True,
        "serverJavaVersion": "21.0.10",
    }, .8))

    result = benchmark.request_once("http://server", {"name": "semantic-only", "mode": "semantic"},
                                    0, 1, manifest(), 1)

    assert result["timeoutComponents"] == ["SEMANTIC_TIMEOUT"]
    assert result["degradedComponents"] == ["SEMANTIC_TIMEOUT"]
    assert result["timeoutStatus"] == "TIMED_OUT_AT_CHANNEL_BUDGET"
    assert result["performanceLatencyMs"]["semantic.queueWaitMs"] == 12.5


def test_warmup_diagnostics_are_separate_from_measured_aggregation(monkeypatch):
    def response(_target, _workload, request_number, *_args):
        warmup = request_number < 0
        return {
            "error": False, "degraded": warmup, "timeout": warmup,
            "latencyMs": 999 if warmup else 10,
            "stageLatencyMs": {},
            "performanceLatencyMs": {"semantic.queueWaitMs": 999 if warmup else 10},
            "performanceAttributes": {},
            "timeoutComponents": ["SEMANTIC_ADMISSION_TIMEOUT"] if warmup else [],
            "degradedComponents": ["SEMANTIC_ADMISSION_TIMEOUT"] if warmup else [],
            "timeoutStatus": "TIMED_OUT_AT_CHANNEL_BUDGET" if warmup else "NONE",
            "staleCandidateCount": 0, "activeBuildTruncated": False,
            "sentinelRecall": False, "boundedResults": True, "evidenceStatus": "VALID",
            "serverJavaVersion": "21.0.10",
        }

    monkeypatch.setattr(benchmark, "request_once", response)
    options = SimpleNamespace(requests_per_workload=1, warmup_requests=1,
                              dataset_id=1, timeout_seconds=1)

    result = benchmark.run_workload("http://server", {"name": "semantic-only", "mode": "semantic"},
                                    1, options, manifest())

    assert result["timeoutRate"] == 0
    assert result["degradedRate"] == 0
    assert result["performanceLatencyMs"]["semantic.queueWaitMs"]["p50"] == 10
    assert result["warmupDiagnostics"]["stages"]["semantic.queueWaitMs"]["p50Ms"] == 999
    assert result["warmupDiagnostics"]["semanticOutcomes"]["admissionTimeouts"] == 1


def test_measured_diagnostics_classify_admission_rejection_and_execution_timeout():
    diagnostics = benchmark.diagnostic_summary([
        {"performanceLatencyMs": {"semantic.channelMs": 20},
         "performanceAttributes": {"semantic.annCandidateBudget": "160",
                                    "semantic.annRefillRounds": "1",
                                    "semantic.annRefillExhausted": "false"},
         "degradedComponents": ["SEMANTIC_ADMISSION_TIMEOUT", "LEXICAL_REJECTED"]},
        {"performanceLatencyMs": {"semantic.channelMs": 30},
         "performanceAttributes": {"semantic.dbStatementTimeout": "true"},
         "degradedComponents": ["SEMANTIC_TIMEOUT", "LEXICAL_TIMEOUT"]},
    ])

    assert diagnostics["requestCount"] == 2
    assert diagnostics["semanticOutcomes"]["annRequests"] == 1
    assert diagnostics["semanticOutcomes"]["annRefilled"] == 1
    assert diagnostics["semanticOutcomes"]["dbStatementTimeouts"] == 1
    assert diagnostics["semanticOutcomes"]["admissionTimeouts"] == 1
    assert diagnostics["semanticOutcomes"]["executionTimeouts"] == 1
    assert diagnostics["lexicalOutcomes"]["rejected"] == 1
