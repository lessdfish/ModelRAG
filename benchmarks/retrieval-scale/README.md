# Retrieval-scale benchmark

This benchmark is an opt-in, real-runtime harness for G11 2.0.27. It does not
invent latency, throughput, retrieval quality, or scale results. The target,
PostgreSQL, and Elasticsearch must be reachable before a run is started.

The benchmark identity is deterministic:

- `smoke`: 20,000 retrieval units and 2,000 active documents;
- `full`: 1,000,000 active retrieval units, 20,001 active documents, 1024-d
  vectors, stale/superseded builds, and 20,001 active builds (above the
  default `modelrag.retrieval.v2.max-active-build-filter` of 10,000);
- every run uses the shared V2 index `modelrag-retrieval-units-v2`;
- workloads are semantic-only, lexical-only, hybrid, document-scoped, broad,
  stale-build exclusion, and high-active-build-count;
- each workload is exercised at concurrency 1, 8, and 32.

## Generate a deterministic fixture

```powershell
python benchmarks/retrieval-scale/generate/generate_dataset.py `
  --mode smoke --output $env:TEMP\modelrag-retrieval-smoke
```

The full fixture is intentionally expensive and must be explicitly requested.
`--include-vectors` is optional when inspecting a self-contained JSONL fixture;
the loader otherwise derives the same vectors from each row's deterministic seed.

```powershell
python benchmarks/retrieval-scale/generate/generate_dataset.py `
  --mode full --output D:\modelrag\retrieval-full
```

The manifest declares the expected topology; it is not benchmark evidence.
Before executing workloads, the runner independently verifies PostgreSQL and
Elasticsearch counts, profile/dimension, shared-index data, and fixture identity.
Any mismatch prints `NOT RUN` and no completed report is written.

## Load the fixture into a dedicated runtime

The loader streams JSONL in batches, generates each deterministic 1024-d vector
on demand, writes PostgreSQL/pgvector and the shared Elasticsearch index, and
never retains the full vector set in memory. It deliberately refuses to load
when dataset id `1` already exists; use a dedicated empty benchmark database.

```powershell
python benchmarks/retrieval-scale/load/load_all.py `
  --manifest $env:TEMP\modelrag-retrieval-smoke\manifest.json `
  --database-url $env:MODELRAG_BENCHMARK_DATABASE_URL `
  --elasticsearch-url $env:MODELRAG_BENCHMARK_ES_URL
```

## Execute against a real runtime

The target is an explicitly configured internal benchmark endpoint. It must
accept a POST body containing `datasetId`, `query`, `workload`, `mode`,
`documentId`, and `staleBuildId`, and return HTTP 2xx JSON. The response may
include `degraded` (boolean or string list), `timeout` (boolean), and
`stageLatencyMs` (object). A target is deliberately not guessed or silently
substituted with a fake implementation.

```powershell
$env:MODELRAG_BENCHMARK_TARGET = 'http://localhost:8091/internal/benchmarks/retrieval'
$env:MODELRAG_BENCHMARK_DATABASE_URL = 'postgresql://user:password@localhost:15432/modelrag'
$env:MODELRAG_BENCHMARK_ES_URL = 'http://localhost:19200'

python benchmarks/retrieval-scale/run/benchmark.py `
  --mode smoke `
  --manifest $env:TEMP\modelrag-retrieval-smoke\manifest.json `
  --requests-per-workload 100 `
  --report-dir benchmarks/retrieval-scale/reports
```

The runner captures p50/p95/p99, throughput, HTTP errors, degraded responses,
timeouts, stage latency when returned, and PostgreSQL
`EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON)` for bounded retrieval predicates.
Reports include the git commit, fixture manifest hash, verified PG/ES topology, runtime endpoints
without credentials, JVM/Python versions, and hardware information.

If the full-scale infrastructure is unavailable, the command prints
`NOT RUN: <reason>` and exits without a result report. A smoke run must never
be presented as a full run.
