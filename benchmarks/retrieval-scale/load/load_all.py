#!/usr/bin/env python3
"""Load deterministic benchmark JSONL into PostgreSQL/pgvector and Elasticsearch in bounded batches."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any, Iterable

INDEX = "modelrag-retrieval-units-v2"
PROFILE = "qwen3-v1"
DIMENSIONS = 1024


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--manifest", type=Path, required=True)
    parser.add_argument("--database-url", required=True)
    parser.add_argument("--elasticsearch-url", required=True)
    parser.add_argument("--batch-size", type=int, default=250)
    return parser.parse_args()


def rows(path: Path) -> Iterable[dict[str, Any]]:
    with path.open("r", encoding="utf-8") as stream:
        for number, line in enumerate(stream, 1):
            if line.strip():
                try:
                    yield json.loads(line)
                except json.JSONDecodeError as error:
                    raise RuntimeError(f"invalid JSONL at {path.name}:{number}") from error


def batches(values: Iterable[dict[str, Any]], size: int) -> Iterable[list[dict[str, Any]]]:
    batch: list[dict[str, Any]] = []
    for value in values:
        batch.append(value)
        if len(batch) >= size:
            yield batch
            batch = []
    if batch:
        yield batch


def embedding(row: dict[str, Any], seed: int) -> str:
    values = row.get("embedding")
    if values is None:
        generator = random.Random(int(row.get("embeddingSeed", (seed << 32) ^ int(row["retrievalUnitId"]))))
        values = [round(generator.uniform(-1.0, 1.0), 7) for _ in range(DIMENSIONS)]
    if len(values) != DIMENSIONS:
        raise RuntimeError(f"retrieval unit {row['retrievalUnitId']} has invalid vector dimension")
    return "[" + ",".join(str(value) for value in values) + "]"


def request(url: str, method: str, body: bytes | None = None, content_type: str = "application/json") -> bytes:
    call = urllib.request.Request(url, data=body, method=method,
                                 headers={"Content-Type": content_type} if body is not None else {})
    try:
        with urllib.request.urlopen(call, timeout=60) as response:
            return response.read()
    except urllib.error.HTTPError as error:
        detail = error.read().decode("utf-8", errors="replace")[:500]
        raise RuntimeError(f"Elasticsearch HTTP {error.code}: {detail}") from error


def ensure_index(endpoint: str) -> None:
    url = endpoint.rstrip("/") + "/" + INDEX
    try:
        request(url, "HEAD")
        return
    except RuntimeError:
        pass
    mapping = {"mappings": {"properties": {
        "datasetId": {"type": "long"}, "documentId": {"type": "long"},
        "documentVersionId": {"type": "long"}, "nodeId": {"type": "long"},
        "retrievalUnitId": {"type": "long"}, "indexBuildId": {"type": "long"},
        "unitType": {"type": "keyword"}, "titlePath": {"type": "text"},
        "content": {"type": "text"}, "fixtureIdentity": {"type": "keyword"},
        "benchmarkIdentity": {"type": "keyword"}, "metadata": {"type": "object", "enabled": True}
    }}}
    request(url, "PUT", json.dumps(mapping).encode("utf-8"))


def bulk_index(endpoint: str, fixture: str, values: list[dict[str, Any]]) -> None:
    payload: list[str] = []
    for row in values:
        unit_id = int(row["retrievalUnitId"])
        source = {key: row[key] for key in ("datasetId", "documentId", "documentVersionId", "nodeId",
                                             "retrievalUnitId", "indexBuildId", "content")}
        source.update({"unitType": "PARAGRAPH", "titlePath": f"Benchmark / {row['documentId']}",
                       "fixtureIdentity": fixture, "benchmarkIdentity": fixture,
                       "metadata": {"fixtureIdentity": fixture, "active": bool(row.get("active"))}})
        payload.append(json.dumps({"index": {"_index": INDEX, "_id": str(unit_id)}}, separators=(",", ":")))
        payload.append(json.dumps(source, separators=(",", ":")))
    body = ("\n".join(payload) + "\n").encode("utf-8")
    result = json.loads(request(endpoint.rstrip("/") + "/_bulk?refresh=false", "POST", body,
                                "application/x-ndjson"))
    if result.get("errors"):
        raise RuntimeError("Elasticsearch bulk request contained item failures")


def load_postgres(connection: Any, root: Path, manifest: dict[str, Any], batch_size: int,
                  es_endpoint: str) -> None:
    fixture = str(manifest["fixtureIdentity"])
    dataset_id = 1
    documents_path = root / manifest["files"]["documents"]
    units_path = root / manifest["files"]["retrievalUnits"]
    with connection.cursor() as cursor:
        cursor.execute("SELECT COUNT(*) FROM kb_dataset WHERE id=%s", (dataset_id,))
        if cursor.fetchone()[0]:
            raise RuntimeError("benchmark dataset id 1 already exists; use a dedicated empty benchmark database")
        cursor.execute("""
            INSERT INTO kb_dataset(id,name,description,embedding_model,llm_model,rerank_model,top_k,
                                   similarity_threshold,status)
            VALUES (%s,%s,%s,%s,'benchmark','benchmark',20,0.0,'ACTIVE')
            """, (dataset_id, f"retrieval-scale-{fixture[:12]}", fixture, "Qwen3-Embedding-0.6B"))
    connection.commit()

    for batch in batches(rows(documents_path), batch_size):
        document_values = []
        version_values = []
        node_values = []
        build_values = []
        stale_build_values = []
        for row in batch:
            document_id = int(row["documentId"])
            build_id = int(row["activeBuildId"])
            metadata = json.dumps({"fixtureIdentity": fixture}, separators=(",", ":"))
            document_values.append((document_id, dataset_id, f"benchmark-{document_id}.txt", "txt", "INDEXED"))
            version_values.append((document_id, document_id, 1, "READY", metadata))
            node_values.append((document_id, dataset_id, document_id, document_id, "DOCUMENT", 0, 0,
                                f"Benchmark {document_id}", metadata))
            build_values.append((build_id, dataset_id, document_id, document_id, 1, "ACTIVE", PROFILE,
                                 "benchmark", metadata))
            stale_build_values.append((int(manifest["activeBuildCount"]) + document_id, dataset_id,
                                       document_id, document_id, 2, "SUPERSEDED", PROFILE,
                                       "benchmark", metadata))
        with connection.cursor() as cursor:
            cursor.executemany("INSERT INTO kb_document(id,dataset_id,file_name,file_type,index_status) VALUES (%s,%s,%s,%s,%s)", document_values)
            cursor.executemany("INSERT INTO kb_document_version(id,document_id,version_no,parse_status,metadata) VALUES (%s,%s,%s,%s,%s::jsonb)", version_values)
            cursor.executemany("INSERT INTO kb_document_node(id,dataset_id,document_id,document_version_id,node_type,depth,ordinal,title,metadata) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)", node_values)
            cursor.executemany("INSERT INTO kb_index_build(id,dataset_id,document_id,document_version_id,build_no,state,embedding_profile,rerank_profile,metadata) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)", build_values)
            cursor.executemany("INSERT INTO kb_index_build(id,dataset_id,document_id,document_version_id,build_no,state,embedding_profile,rerank_profile,metadata) VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)", stale_build_values)
            cursor.executemany("UPDATE kb_document SET active_version_id=%s,active_index_build_id=%s WHERE id=%s",
                               [(value[0], build_values[index][0], value[0]) for index, value in enumerate(version_values)])
        connection.commit()

    seed = int(manifest["seed"])
    document_count = int(manifest["activeDocuments"])
    for batch in batches(rows(units_path), batch_size):
        unit_values = []
        vector_values = []
        for row in batch:
            unit_id = int(row["retrievalUnitId"])
            document_id = int(row["documentId"])
            ordinal = (unit_id - 1) // document_count
            metadata = json.dumps({"fixtureIdentity": fixture, "active": bool(row.get("active"))},
                                  separators=(",", ":"))
            digest = hashlib.sha256(row["content"].encode("utf-8")).hexdigest()
            unit_values.append((unit_id, dataset_id, document_id, document_id, document_id,
                                int(row["indexBuildId"]), "PARAGRAPH", ordinal,
                                f"Benchmark / {document_id}", row["content"], digest, 16, metadata))
            vector_values.append((unit_id, unit_id, dataset_id, document_id, document_id,
                                  int(row["indexBuildId"]), PROFILE, embedding(row, seed)))
        with connection.cursor() as cursor:
            cursor.executemany("""
                INSERT INTO kb_retrieval_unit(id,dataset_id,document_id,document_version_id,node_id,index_build_id,
                    unit_type,ordinal,title_path,content,content_hash,token_count,metadata)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s::jsonb)
                """, unit_values)
            cursor.executemany("""
                INSERT INTO kb_vector_embedding(id,retrieval_unit_id,dataset_id,document_id,document_version_id,
                    index_build_id,embedding_profile,embedding)
                VALUES (%s,%s,%s,%s,%s,%s,%s,%s::vector)
                """, vector_values)
        connection.commit()
        bulk_index(es_endpoint, fixture, batch)

    with connection.cursor() as cursor:
        for table in ("kb_document", "kb_document_version", "kb_document_node", "kb_index_build",
                      "kb_retrieval_unit", "kb_vector_embedding"):
            cursor.execute("SELECT setval(pg_get_serial_sequence(%s,'id'), COALESCE((SELECT MAX(id) FROM " + table + "),1), true)", (table,))
    connection.commit()
    request(es_endpoint.rstrip("/") + "/" + INDEX + "/_refresh", "POST")


def main() -> int:
    options = arguments()
    if options.batch_size < 1 or options.batch_size > 500:
        raise SystemExit("batch-size must be between 1 and 500")
    manifest_path = options.manifest.resolve()
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    if manifest.get("vectorDimension") != DIMENSIONS or manifest.get("sharedV2Index") != INDEX:
        raise SystemExit("fixture profile/index does not match qwen3-v1 1024-d shared-index contract")
    try:
        import psycopg
    except ImportError as error:
        raise SystemExit("psycopg is required: python -m pip install 'psycopg[binary]'") from error
    ensure_index(options.elasticsearch_url)
    with psycopg.connect(options.database_url) as connection:
        load_postgres(connection, manifest_path.parent, manifest, options.batch_size, options.elasticsearch_url)
    print(json.dumps({"status": "LOADED", "fixtureIdentity": manifest["fixtureIdentity"],
                      "activeRetrievalUnits": manifest["activeRetrievalUnits"],
                      "staleRetrievalUnits": manifest["staleRetrievalUnits"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
