#!/usr/bin/env python3
"""Execute real retrieval-scale workloads and write measured reports only."""

from __future__ import annotations

import argparse
import concurrent.futures
import datetime as dt
import hashlib
import json
import os
import platform
import re
import subprocess
import time
import urllib.error
import urllib.request
from collections import Counter
from pathlib import Path
from typing import Any

SHARED_INDEX = "modelrag-retrieval-units-v2"
REQUIRED_WORKLOADS = {
    "semantic-only", "lexical-only", "hybrid", "document-scoped", "broad",
    "stale-build-exclusion", "high-active-build-count",
}
CONCURRENCIES = (1, 8, 32)


def args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("smoke", "full"), required=True)
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--workloads", type=Path,
                        default=Path(__file__).parents[1] / "workloads" / "workloads.json")
    parser.add_argument("--report-dir", type=Path,
                        default=Path(__file__).parents[1] / "reports")
    parser.add_argument("--requests-per-workload", type=int, default=100)
    parser.add_argument("--warmup-requests", type=int, default=10)
    parser.add_argument("--timeout-seconds", type=float, default=30.0)
    parser.add_argument("--concurrency", type=int, choices=CONCURRENCIES, action="append")
    parser.add_argument("--workload", choices=sorted(REQUIRED_WORKLOADS), action="append",
                        help="run selected smoke workload(s); full mode always requires all workloads")
    parser.add_argument("--dataset-id", type=int, default=1)
    return parser.parse_args()


def not_run(reason: str) -> int:
    print(f"NOT RUN: {reason}")
    return 0


def load_json(path: Path) -> Any:
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        raise RuntimeError(f"cannot read {path}: {error}") from error


def validate_manifest(manifest: dict[str, Any], mode: str) -> list[str]:
    errors: list[str] = []
    if manifest.get("mode") != mode:
        errors.append(f"manifest mode is {manifest.get('mode')!r}, expected {mode!r}")
    if mode == "smoke":
        units = int(manifest.get("activeRetrievalUnits", 0))
        if not 10_000 <= units <= 50_000:
            errors.append("smoke manifest must contain 10,000-50,000 active retrieval units")
    else:
        if int(manifest.get("activeRetrievalUnits", 0)) < 1_000_000:
            errors.append("full manifest has fewer than 1,000,000 active retrieval units")
        if int(manifest.get("activeDocuments", 0)) < 20_000:
            errors.append("full manifest has fewer than 20,000 active documents")
        if int(manifest.get("activeBuildCount", 0)) <= int(manifest.get("activeBuildFilterLimit", 10_000)):
            errors.append("full manifest does not exceed the active-build filter cap")
        if int(manifest.get("staleRetrievalUnits", 0)) <= 0:
            errors.append("full manifest has no stale retrieval units")
    if int(manifest.get("vectorDimension", 0)) != 1024:
        errors.append("vector dimension is not 1024")
    if manifest.get("sharedV2Index") != SHARED_INDEX:
        errors.append(f"shared V2 index is not {SHARED_INDEX}")
    if not manifest.get("hasStaleBuilds"):
        errors.append("stale/superseded builds are missing")
    sentinel = str(manifest.get("activeBuildSentinel", ""))
    expected_sentinel = f"G11_ACTIVE_BUILD_SENTINEL_{int(manifest.get('activeBuildCount', 0))}"
    if sentinel != expected_sentinel:
        errors.append("active-build sentinel does not identify the last active build")
    return errors


def validate_workloads(values: Any) -> tuple[list[dict[str, Any]], list[str]]:
    if not isinstance(values, list):
        return [], ["workload definition must be a list"]
    workloads = [value for value in values if isinstance(value, dict)]
    names = {str(value.get("name")) for value in workloads}
    missing = sorted(REQUIRED_WORKLOADS - names)
    return workloads, [f"missing workload: {name}" for name in missing]


def http_json(url: str, body: dict[str, Any] | None, timeout: float,
              headers: dict[str, str] | None = None) -> tuple[int, dict[str, Any], float]:
    data = None if body is None else json.dumps(body, separators=(",", ":")).encode("utf-8")
    request = urllib.request.Request(url, data=data, method="POST" if body is not None else "GET")
    if body is not None:
        request.add_header("Content-Type", "application/json")
    for name, value in (headers or {}).items():
        request.add_header(name, value)
    started = time.perf_counter()
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            payload = response.read().decode("utf-8")
            value = json.loads(payload) if payload else {}
            return response.status, value if isinstance(value, dict) else {}, time.perf_counter() - started
    except urllib.error.HTTPError as error:
        return error.code, {}, time.perf_counter() - started
    except (urllib.error.URLError, TimeoutError, OSError, json.JSONDecodeError):
        return 0, {}, time.perf_counter() - started


def check_runtime(target: str, database_url: str, es_url: str, timeout: float) -> str | None:
    if not target:
        return "MODELRAG_BENCHMARK_TARGET is not configured"
    if not database_url:
        return "MODELRAG_BENCHMARK_DATABASE_URL is not configured"
    if not es_url:
        return "MODELRAG_BENCHMARK_ES_URL is not configured"
    status, _, _ = http_json(es_url.rstrip("/") + "/_cluster/health", None, timeout)
    if status < 200 or status >= 300:
        return f"Elasticsearch health is unavailable (HTTP {status or 'connection failure'})"
    index_status, _, _ = http_json(es_url.rstrip("/") + "/" + SHARED_INDEX, None, timeout)
    if index_status < 200 or index_status >= 300:
        return f"shared V2 Elasticsearch index is unavailable (HTTP {index_status or 'connection failure'})"
    try:
        import psycopg  # type: ignore
        with psycopg.connect(database_url, connect_timeout=max(1, int(timeout))) as connection:
            with connection.cursor() as cursor:
                cursor.execute("SELECT 1")
                cursor.fetchone()
    except ImportError:
        return "psycopg is not installed for EXPLAIN ANALYZE capture"
    except Exception as error:
        return f"PostgreSQL is unavailable ({type(error).__name__})"
    return None


def preflight(database_url: str, es_url: str, dataset_id: int, manifest: dict[str, Any],
              timeout: float) -> tuple[dict[str, Any], list[str]]:
    fixture = str(manifest.get("fixtureIdentity", ""))
    expected = {
        "activeRetrievalUnits": int(manifest.get("activeRetrievalUnits", 0)),
        "activeDocuments": int(manifest.get("activeDocuments", 0)),
        "activeBuildCount": int(manifest.get("activeBuildCount", 0)),
        "staleRetrievalUnits": int(manifest.get("staleRetrievalUnits", 0)),
        "vectorDimension": int(manifest.get("vectorDimension", 0)),
        "embeddingProfile": "qwen3-v1",
        "fixtureIdentity": fixture,
    }
    actual: dict[str, Any] = {"postgres": {}, "elasticsearch": {}}
    errors: list[str] = []
    import psycopg  # type: ignore
    with psycopg.connect(database_url, connect_timeout=max(1, int(timeout))) as connection:
        with connection.cursor() as cursor:
            cursor.execute("""
                SELECT COUNT(*) FILTER (WHERE b.state='ACTIVE' AND d.active_index_build_id=u.index_build_id
                            AND d.active_version_id=u.document_version_id),
                       COUNT(*) FILTER (WHERE NOT (b.state='ACTIVE' AND d.active_index_build_id=u.index_build_id
                            AND d.active_version_id=u.document_version_id))
                FROM kb_retrieval_unit u JOIN kb_document d ON d.id=u.document_id
                JOIN kb_index_build b ON b.id=u.index_build_id
                JOIN kb_dataset ds ON ds.id=u.dataset_id
                WHERE u.dataset_id=%s AND u.metadata->>'fixtureIdentity'=%s
                  AND d.delete_time IS NULL AND ds.delete_time IS NULL
            """, (dataset_id, fixture))
            active_units, stale_units = cursor.fetchone()
            cursor.execute("""
                SELECT COUNT(*) FROM kb_document d
                JOIN kb_index_build b ON b.id=d.active_index_build_id AND b.state='ACTIVE'
                JOIN kb_dataset ds ON ds.id=d.dataset_id
                WHERE d.dataset_id=%s AND d.delete_time IS NULL AND ds.delete_time IS NULL
                  AND b.metadata->>'fixtureIdentity'=%s
            """, (dataset_id, fixture))
            active_documents = cursor.fetchone()[0]
            cursor.execute("SELECT COUNT(*) FROM kb_index_build WHERE dataset_id=%s AND state='ACTIVE' AND metadata->>'fixtureIdentity'=%s", (dataset_id, fixture))
            active_builds = cursor.fetchone()[0]
            cursor.execute("""
                SELECT COUNT(*), COALESCE(MIN(vector_dims(e.embedding)),0),
                       COALESCE(MAX(vector_dims(e.embedding)),0),
                       COALESCE(MIN(e.embedding_profile),'')
                FROM kb_vector_embedding e JOIN kb_retrieval_unit u ON u.id=e.retrieval_unit_id
                WHERE e.dataset_id=%s AND u.metadata->>'fixtureIdentity'=%s
            """, (dataset_id, fixture))
            vector_count, min_dimension, max_dimension, profile = cursor.fetchone()
            cursor.execute("SELECT COUNT(DISTINCT metadata->>'fixtureIdentity') FROM kb_retrieval_unit WHERE dataset_id=%s", (dataset_id,))
            fixture_count = cursor.fetchone()[0]
    actual["postgres"] = {"activeRetrievalUnits": active_units, "activeDocuments": active_documents,
                          "activeBuildCount": active_builds, "staleRetrievalUnits": stale_units,
                          "vectorCount": vector_count, "vectorDimension": min_dimension if min_dimension == max_dimension else -1,
                          "embeddingProfile": profile, "fixtureIdentity": fixture if fixture_count == 1 else "MIXED"}

    query = {"query": {"bool": {"filter": [
        {"term": {"datasetId": dataset_id}}, {"term": {"fixtureIdentity": fixture}},
        {"term": {"benchmarkIdentity": fixture}}
    ]}}}
    status, es_count, _ = http_json(es_url.rstrip("/") + "/" + SHARED_INDEX + "/_count", query, timeout)
    if status < 200 or status >= 300:
        errors.append("Elasticsearch fixture count failed")
        count = -1
    else:
        count = int(es_count.get("count", -1))
    dataset_query = {"query": {"term": {"datasetId": dataset_id}}}
    dataset_status, dataset_count_response, _ = http_json(
        es_url.rstrip("/") + "/" + SHARED_INDEX + "/_count", dataset_query, timeout)
    dataset_count = int(dataset_count_response.get("count", -1)) if 200 <= dataset_status < 300 else -1
    actual["elasticsearch"] = {"index": SHARED_INDEX, "fixtureIdentity": fixture,
                               "benchmarkIdentity": fixture, "fixtureRetrievalUnits": count,
                               "datasetRetrievalUnits": dataset_count}

    for key in ("activeRetrievalUnits", "activeDocuments", "activeBuildCount", "staleRetrievalUnits",
                "vectorDimension", "embeddingProfile", "fixtureIdentity"):
        if actual["postgres"].get(key) != expected[key]:
            errors.append(f"postgres.actual.{key}={actual['postgres'].get(key)!r} != manifest.expected.{key}={expected[key]!r}")
    if actual["postgres"].get("vectorCount") != expected["activeRetrievalUnits"] + expected["staleRetrievalUnits"]:
        errors.append("PostgreSQL vector count does not match fixture unit count")
    if count != expected["activeRetrievalUnits"] + expected["staleRetrievalUnits"]:
        errors.append("Elasticsearch fixture count does not match manifest")
    if dataset_count != count:
        errors.append("Elasticsearch dataset contains a different fixtureIdentity/benchmarkIdentity")
    return {"expected": expected, **actual}, errors


def explain(database_url: str, dataset_id: int, timeout: float) -> dict[str, Any]:
    import psycopg  # type: ignore
    query = """
        EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)
        WITH ann AS MATERIALIZED (
            SELECT e.retrieval_unit_id,e.dataset_id,e.document_id,e.document_version_id,e.index_build_id,
                   e.embedding <=> CAST(%s AS vector) AS distance
            FROM kb_vector_embedding e
            WHERE e.dataset_id=%s AND e.embedding_profile='qwen3-v1'
            ORDER BY e.embedding <=> CAST(%s AS vector)
            LIMIT 80
        )
        SELECT u.id,u.document_id,u.node_id,u.index_build_id,1-ann.distance AS score
        FROM ann
        JOIN kb_retrieval_unit u ON u.id=ann.retrieval_unit_id AND u.dataset_id=ann.dataset_id
          AND u.document_id=ann.document_id AND u.document_version_id=ann.document_version_id
          AND u.index_build_id=ann.index_build_id
        JOIN kb_index_build b ON b.id=u.index_build_id
        JOIN kb_document d ON d.id=u.document_id
        JOIN kb_dataset ds ON ds.id=u.dataset_id
        WHERE b.state='ACTIVE' AND d.delete_time IS NULL AND ds.delete_time IS NULL
          AND d.active_index_build_id=u.index_build_id
          AND d.active_version_id=u.document_version_id
        ORDER BY ann.distance
        LIMIT 20
    """
    try:
        with psycopg.connect(database_url, connect_timeout=max(1, int(timeout))) as connection:
            with connection.cursor() as cursor:
                cursor.execute("""
                    SELECT embedding::text FROM kb_vector_embedding
                    WHERE dataset_id=%s AND embedding_profile='qwen3-v1'
                    ORDER BY retrieval_unit_id LIMIT 1
                """, (dataset_id,))
                vector_row = cursor.fetchone()
                if not vector_row:
                    return {"query": "ann_first_semantic_retrieval", "error": "no_query_vector"}
                cursor.execute("SET LOCAL hnsw.iterative_scan = 'relaxed_order'")
                cursor.execute(query, (vector_row[0], dataset_id, vector_row[0]))
                row = cursor.fetchone()
                return {"query": "ann_first_semantic_retrieval", "candidateBudget": 80,
                        "finalTopK": 20, "plan": row[0] if row else None}
    except Exception as error:
        return {"query": "ann_first_semantic_retrieval", "error": type(error).__name__}


def percentile(values: list[float], fraction: float) -> float | None:
    if not values:
        return None
    values = sorted(values)
    index = min(len(values) - 1, max(0, int(round((len(values) - 1) * fraction))))
    return round(values[index] * 1000, 3)


def request_once(target: str, workload: dict[str, Any], request_number: int, dataset_id: int,
                manifest: dict[str, Any], timeout: float) -> dict[str, Any]:
    query = (str(manifest.get("activeBuildSentinel", ""))
             if workload.get("querySource") == "activeBuildSentinel"
             else f"G11 deterministic {workload['name']} query {request_number % 20}")
    active_build_overflow = (int(manifest.get("activeBuildCount", 0))
                             > int(manifest.get("activeBuildFilterLimit", 10_000)))
    body = {
        "datasetId": dataset_id,
        "query": query,
        "workload": workload["name"],
        "mode": workload.get("mode", "hybrid"),
        "documentId": 1 if workload.get("documentScoped") else None,
        "staleBuildId": int(manifest.get("activeBuildCount", 1)) + 1 if workload.get("staleFilter") else None,
        "activeBuildOverflow": bool(workload.get("activeBuildOverflow", False)) and active_build_overflow,
        "benchmarkIdentity": manifest.get("fixtureIdentity", "unknown"),
    }
    token = os.environ.get("MODELRAG_BENCHMARK_AUTH_TOKEN", "").strip()
    headers = {"Authorization": f"Bearer {token}"} if token else None
    status, response, elapsed = http_json(target, body, timeout, headers)
    degraded_value = response.get("degraded", False)
    degraded = (degraded_value.strip().lower() not in {"", "false", "0", "none", "[]"}
                 if isinstance(degraded_value, str) else bool(degraded_value))
    candidate_count = int(response.get("candidateCount", 0))
    performance_latency = response.get("performanceLatencyMs", {})
    if not isinstance(performance_latency, dict):
        performance_latency = {}
    performance_attributes = response.get("performanceAttributes", {})
    if not isinstance(performance_attributes, dict):
        performance_attributes = {}
    timeout_components = response.get("timeoutComponents", [])
    if not isinstance(timeout_components, list):
        timeout_components = []
    evidence_status = str(response.get("evidenceStatus", "")).strip().upper()
    if evidence_status not in {"NO_EVIDENCE", "VALID", "INVALID"}:
        evidence_status = ("NO_EVIDENCE" if candidate_count == 0 else
                           "VALID" if bool(response.get("evidenceValid", False)) else "INVALID")
    return {
        "latencyMs": elapsed * 1000,
        "status": status,
        "error": status < 200 or status >= 300,
        "timeout": bool(response.get("timeout", False)) or status == 0,
        "degraded": bool(degraded),
        "stageLatencyMs": response.get("stageLatencyMs", {}),
        "performanceLatencyMs": performance_latency,
        "performanceAttributes": performance_attributes,
        "timeoutComponents": [str(value) for value in timeout_components],
        "degradedComponents": [str(value) for value in response.get("degradedComponents", [])]
                              if isinstance(response.get("degradedComponents", []), list) else [],
        "timeoutStatus": str(response.get("timeoutStatus", "NONE")),
        "candidateCount": candidate_count,
        "staleCandidateCount": int(response.get("staleCandidateCount", 0)),
        "activeBuildTruncated": bool(response.get("activeBuildTruncated", False)),
        "sentinelRecall": bool(response.get("sentinelRecall", False)),
        "boundedResults": bool(response.get("boundedResults", False)),
        "evidenceStatus": evidence_status,
        "serverJavaVersion": (response.get("serverJavaVersion", "").strip()
                              if isinstance(response.get("serverJavaVersion", ""), str) else ""),
    }


def run_workload(target: str, workload: dict[str, Any], concurrency: int, options: argparse.Namespace,
                  manifest: dict[str, Any]) -> dict[str, Any]:
    count = max(1, options.requests_per_workload)
    warmup = max(0, options.warmup_requests)
    warmup_results: list[dict[str, Any]] = []
    if warmup:
        with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
            warmup_futures = [pool.submit(request_once, target, workload, -index - 1, options.dataset_id,
                                          manifest, options.timeout_seconds) for index in range(warmup)]
            for future in warmup_futures:
                warmup_results.append(future.result())
    started = time.perf_counter()
    with concurrent.futures.ThreadPoolExecutor(max_workers=concurrency) as pool:
        futures = [pool.submit(request_once, target, workload, index, options.dataset_id, manifest,
                               options.timeout_seconds) for index in range(count)]
        results = [future.result() for future in futures]
    duration = max(0.000001, time.perf_counter() - started)
    latencies = [float(value["latencyMs"]) / 1000 for value in results]
    errors = sum(1 for value in results if value["error"])
    degraded = sum(1 for value in results if value["degraded"])
    timeouts = sum(1 for value in results if value["timeout"])
    stage_values: dict[str, list[float]] = {}
    performance_values: dict[str, list[float]] = {}
    timeout_components: Counter[str] = Counter()
    cache_outcomes: Counter[str] = Counter()
    for value in results:
        for stage, latency in (value.get("stageLatencyMs") or {}).items():
            if isinstance(latency, (int, float)):
                stage_values.setdefault(str(stage), []).append(float(latency) / 1000)
        for stage, latency in (value.get("performanceLatencyMs") or {}).items():
            if isinstance(latency, (int, float)):
                performance_values.setdefault(str(stage), []).append(float(latency) / 1000)
        timeout_components.update(value.get("timeoutComponents") or [])
        outcome = str((value.get("performanceAttributes") or {}).get("semantic.embeddingCacheOutcome", ""))
        if outcome:
            cache_outcomes[outcome] += 1
    return {
        "workload": workload["name"],
        "mode": workload.get("mode"),
        "concurrency": concurrency,
        "requests": count,
        "warmupRequests": warmup,
        "durationSeconds": round(duration, 3),
        "p50Ms": percentile(latencies, .50),
        "p95Ms": percentile(latencies, .95),
        "p99Ms": percentile(latencies, .99),
        "throughputRequestsPerSecond": round(count / duration, 3),
        "errors": errors,
        "errorRate": round(errors / count, 6),
        "degraded": degraded,
        "degradedRate": round(degraded / count, 6),
        "timeouts": timeouts,
        "timeoutRate": round(timeouts / count, 6),
        "requestLatencyMs": {"p50": percentile(latencies, .50), "p95": percentile(latencies, .95),
                             "p99": percentile(latencies, .99)},
        "stageP95Ms": {stage: percentile(values, .95) for stage, values in sorted(stage_values.items())},
        "performanceLatencyMs": {stage: {"count": len(values), "p50": percentile(values, .50),
                                           "p95": percentile(values, .95), "p99": percentile(values, .99)}
                                 for stage, values in sorted(performance_values.items())},
        "timeoutComponents": dict(sorted(timeout_components.items())),
        "timeoutPlateauCount": sum(1 for value in results
                                   if value.get("timeoutStatus") == "TIMED_OUT_AT_CHANNEL_BUDGET"),
        "embeddingCacheOutcomes": dict(sorted(cache_outcomes.items())),
        "staleCandidateCount": sum(value["staleCandidateCount"] for value in results),
        "activeBuildTruncationCount": sum(1 for value in results if value["activeBuildTruncated"]),
        "sentinelRecallFailures": (sum(1 for value in results if not value["sentinelRecall"])
                                   if workload["name"] == "high-active-build-count" else 0),
        "boundedResultFailures": sum(1 for value in results if not value["boundedResults"]),
        "noEvidenceCount": sum(1 for value in results if value["evidenceStatus"] == "NO_EVIDENCE"),
        "invalidEvidenceCount": sum(1 for value in results if value["evidenceStatus"] == "INVALID"),
        "missingServerJavaVersionCount": sum(1 for value in results if not value["serverJavaVersion"].strip()),
        "serverJavaVersions": sorted({value["serverJavaVersion"].strip() for value in results
                                      if value["serverJavaVersion"].strip()}),
        "warmupDiagnostics": diagnostic_summary(warmup_results),
        "_measuredRequests": results,
        "_warmupRequests": warmup_results,
    }


def diagnostic_summary(values: list[dict[str, Any]]) -> dict[str, Any]:
    stages: dict[str, list[float]] = {}
    components: Counter[str] = Counter()
    cache: Counter[str] = Counter()
    for value in values:
        for stage, latency in (value.get("performanceLatencyMs") or {}).items():
            if isinstance(latency, (int, float)):
                stages.setdefault(str(stage), []).append(float(latency) / 1000)
        components.update(value.get("degradedComponents") or [])
        outcome = str((value.get("performanceAttributes") or {}).get("semantic.embeddingCacheOutcome", ""))
        if outcome:
            cache[outcome] += 1
    semantic_attributes = [value.get("performanceAttributes") or {} for value in values]
    semantic = {
        "annRequests": sum(1 for attrs in semantic_attributes if "semantic.annCandidateBudget" in attrs),
        "annRefilled": sum(1 for attrs in semantic_attributes
                           if int(attrs.get("semantic.annRefillRounds", "0")) > 0),
        "annRefillRounds": sum(int(attrs.get("semantic.annRefillRounds", "0"))
                               for attrs in semantic_attributes),
        "annRefillExhausted": sum(1 for attrs in semantic_attributes
                                  if attrs.get("semantic.annRefillExhausted") == "true"),
        "dbStatementTimeouts": sum(1 for attrs in semantic_attributes
                                   if attrs.get("semantic.dbStatementTimeout") == "true"),
        "admissionTimeouts": components["SEMANTIC_ADMISSION_TIMEOUT"],
        "rejected": components["SEMANTIC_REJECTED"],
        "executionTimeouts": components["SEMANTIC_TIMEOUT"],
    }
    lexical = {
        "admissionTimeouts": components["LEXICAL_ADMISSION_TIMEOUT"],
        "rejected": components["LEXICAL_REJECTED"],
        "executionTimeouts": components["LEXICAL_TIMEOUT"],
    }
    return {
        "requestCount": len(values),
        "stages": {stage: {"count": len(samples), "p50Ms": percentile(samples, .50),
                           "p95Ms": percentile(samples, .95), "p99Ms": percentile(samples, .99)}
                   for stage, samples in sorted(stages.items())},
        "degradedComponents": dict(sorted(components.items())),
        "cacheOutcomes": dict(sorted(cache.items())),
        "semanticOutcomes": semantic,
        "lexicalOutcomes": lexical,
    }


def git_commit() -> str:
    try:
        repository = Path(__file__).resolve().parents[3]
        return subprocess.check_output(["git", "-c", f"safe.directory={repository}", "-C", str(repository),
                                        "rev-parse", "HEAD"], text=True,
                                       stderr=subprocess.DEVNULL).strip()
    except (OSError, subprocess.CalledProcessError):
        return "unknown"


def command_version(command: list[str]) -> str:
    try:
        result = subprocess.run(command, capture_output=True, text=True, timeout=5, check=False)
        output = (result.stdout + result.stderr).strip().splitlines()
        return output[0][:200] if output else "unavailable"
    except (OSError, subprocess.SubprocessError):
        return "unavailable"


def build_report(options: argparse.Namespace, manifest: dict[str, Any], workloads: list[dict[str, Any]],
                 results: list[dict[str, Any]], plan: dict[str, Any], verified: dict[str, Any],
                 server_lifetime_diagnostics: dict[str, Any] | None = None) -> dict[str, Any]:
    manifest_text = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
    total_requests = sum(value["requests"] for value in results) or 1
    latencies = [value[key] for value in results for key in ("p50Ms",) if value[key] is not None]
    summary = {"p50Ms": percentile([value / 1000 for value in latencies], .50) or 0,
               "p95Ms": max((value["p95Ms"] or 0 for value in results), default=0),
               "p99Ms": max((value["p99Ms"] or 0 for value in results), default=0),
               "errorRate": sum(value["errors"] for value in results) / total_requests,
               "degradedRate": sum(value["degraded"] for value in results) / total_requests,
               "timeoutRate": sum(value["timeouts"] for value in results) / total_requests,
               "noEvidenceCount": sum(value["noEvidenceCount"] for value in results),
               "invalidEvidenceCount": sum(value["invalidEvidenceCount"] for value in results)}
    server_java_versions = sorted({version for value in results
                                   for version in value.get("serverJavaVersions", []) if version})
    active_build_overflow = (int(manifest.get("activeBuildCount", 0))
                             > int(manifest.get("activeBuildFilterLimit", 10_000)))
    active_build_truncation_status = ("NOT_APPLICABLE" if not active_build_overflow else
                                      "PASS" if sum(value["activeBuildTruncationCount"]
                                                    for value in results) == 0 else "FAIL")
    correctness = {"noStaleBuildLeakage": sum(value["staleCandidateCount"] for value in results) == 0,
                   "sentinelRecall": sum(value["sentinelRecallFailures"] for value in results) == 0,
                   "activeBuildTruncationStatus": active_build_truncation_status,
                   "noActiveBuildTruncation": active_build_truncation_status != "FAIL",
                   "boundedResults": sum(value["boundedResultFailures"] for value in results) == 0,
                   "validEvidence": sum(value["invalidEvidenceCount"] for value in results) == 0}
    measured_requests = [request for result in results for request in result.get("_measuredRequests", [])]
    warmup_requests = [request for result in results for request in result.get("_warmupRequests", [])]
    public_results = [{key: value for key, value in result.items() if not key.startswith("_")}
                      for result in results]
    return {
        "status": "COMPLETED",
        "mode": options.mode,
        "benchmarkIdentity": manifest.get("fixtureIdentity"),
        "gitCommit": git_commit(),
        "fixtureIdentity": manifest.get("fixtureIdentity"),
        "fixtureManifestSha256": hashlib.sha256(manifest_text).hexdigest(),
        "topology": manifest,
        "workloads": [value["name"] for value in workloads],
        "results": public_results,
        "summary": summary,
        "correctness": correctness,
        "preflight": verified,
        "explainAnalyze": plan,
        "performanceDiagnostics": diagnostic_summary(measured_requests),
        "warmupDiagnostics": diagnostic_summary(warmup_requests),
        "serverLifetimeDiagnostics": server_lifetime_diagnostics or {},
        "runtime": {"python": platform.python_version(),
                     "serverJavaVersion": server_java_versions[0] if len(server_java_versions) == 1 else "unavailable",
                     "runnerJavaVersion": command_version(["java", "-version"]),
                     "platform": platform.platform(), "processor": platform.processor()[:200],
                     "target": redact_url(os.environ.get("MODELRAG_BENCHMARK_TARGET", "")),
                     "elasticsearch": redact_url(os.environ.get("MODELRAG_BENCHMARK_ES_URL", ""))},
        "completedAt": dt.datetime.now(dt.timezone.utc).isoformat(),
    }


def write_report(report: dict[str, Any], directory: Path) -> None:
    directory.mkdir(parents=True, exist_ok=True)
    identity = str(report.get("fixtureIdentity") or "unknown")[:16]
    json_path = directory / f"retrieval-scale-{report['mode']}-{identity}.json"
    markdown_path = directory / f"retrieval-scale-{report['mode']}-{identity}.md"
    json_path.write_text(json.dumps(report, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    lines = [f"# Retrieval scale {report['mode']}", "", f"Status: `{report['status']}`",
             f"Commit: `{report['gitCommit']}`", f"Fixture: `{report['fixtureIdentity']}`", "",
             "| Workload | C | warmup | duration s | p50 ms | p95 ms | p99 ms | req/s | errors | error rate | degraded | degraded rate | timeouts | timeout rate | no evidence | invalid evidence | sentinel misses |",
             "|---|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|"]
    for value in report["results"]:
        lines.append("| {workload} | {concurrency} | {warmupRequests} | {durationSeconds} | {p50Ms} | {p95Ms} | "
                     "{p99Ms} | {throughputRequestsPerSecond} | {errors} | {errorRate} | {degraded} | "
                     "{degradedRate} | {timeouts} | {timeoutRate} | {noEvidenceCount} | "
                     "{invalidEvidenceCount} | {sentinelRecallFailures} |".format(**value))
    markdown_path.write_text("\n".join(lines) + "\n", encoding="utf-8")


def full_server_java_error(results: list[dict[str, Any]]) -> str | None:
    if any(value.get("missingServerJavaVersionCount", 0) > 0 for value in results):
        return "benchmark endpoint did not report the server JVM version for every response"
    versions = {version for value in results for version in value.get("serverJavaVersions", []) if version}
    if len(versions) != 1:
        return "benchmark endpoint reported inconsistent server JVM versions"
    if java_major(next(iter(versions))) != 21:
        return "full benchmark server JVM is not Java 21"
    return None


def java_major(version: str) -> int | None:
    match = re.fullmatch(r"(\d+)(?:[._+-].*)?", version.strip())
    return int(match.group(1)) if match else None


def main() -> int:
    options = args()
    if options.requests_per_workload <= 0:
        return not_run("--requests-per-workload must be positive")
    try:
        manifest = load_json(options.manifest)
        workloads, workload_errors = validate_workloads(load_json(options.workloads))
        errors = validate_manifest(manifest, options.mode) + workload_errors
    except RuntimeError as error:
        return not_run(str(error))
    if errors:
        return not_run("; ".join(errors))
    if options.mode == "full" and options.workload:
        return not_run("full mode requires every workload")
    if options.workload:
        selected = set(options.workload)
        workloads = [workload for workload in workloads if workload.get("name") in selected]
    runtime_error = check_runtime(os.environ.get("MODELRAG_BENCHMARK_TARGET", ""),
                                  os.environ.get("MODELRAG_BENCHMARK_DATABASE_URL", ""),
                                  os.environ.get("MODELRAG_BENCHMARK_ES_URL", ""), options.timeout_seconds)
    if runtime_error:
        return not_run(runtime_error)

    verified, preflight_errors = preflight(os.environ["MODELRAG_BENCHMARK_DATABASE_URL"],
                                           os.environ["MODELRAG_BENCHMARK_ES_URL"],
                                           options.dataset_id, manifest, options.timeout_seconds)
    if preflight_errors:
        return not_run("; ".join(preflight_errors))

    target = os.environ["MODELRAG_BENCHMARK_TARGET"]
    concurrencies = options.concurrency or list(CONCURRENCIES)
    if options.mode == "full" and set(concurrencies) != set(CONCURRENCIES):
        return not_run("full mode requires concurrency 1, 8, and 32")
    results = []
    for workload in workloads:
        for concurrency in concurrencies:
            results.append(run_workload(target, workload, concurrency, options, manifest))
    if options.mode == "full":
        server_java_error = full_server_java_error(results)
        if server_java_error:
            return not_run(server_java_error)
    plan = explain(os.environ["MODELRAG_BENCHMARK_DATABASE_URL"], options.dataset_id, options.timeout_seconds)
    token = os.environ.get("MODELRAG_BENCHMARK_AUTH_TOKEN", "").strip()
    headers = {"Authorization": f"Bearer {token}"} if token else None
    diagnostics_status, diagnostics, _ = http_json(target.rstrip("/") + "/diagnostics", None,
                                                    options.timeout_seconds, headers)
    if diagnostics_status < 200 or diagnostics_status >= 300:
        diagnostics = {"status": "UNAVAILABLE", "httpStatus": diagnostics_status}
    report = build_report(options, manifest, workloads, results, plan, verified, diagnostics)
    write_report(report, options.report_dir)
    print(json.dumps({"status": report["status"], "mode": options.mode,
                      "resultCount": len(results), "fixtureIdentity": report["fixtureIdentity"]}, sort_keys=True))
    return 0


def redact_url(value: str) -> str:
    if not value:
        return ""
    try:
        from urllib.parse import urlsplit, urlunsplit
        parsed = urlsplit(value)
        return urlunsplit((parsed.scheme, parsed.hostname or "", parsed.path, "", ""))
    except ValueError:
        return "configured"


if __name__ == "__main__":
    raise SystemExit(main())
