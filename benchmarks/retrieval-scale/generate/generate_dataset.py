#!/usr/bin/env python3
"""Stream deterministic retrieval-scale fixtures without loading all rows."""

from __future__ import annotations

import argparse
import hashlib
import json
import random
from pathlib import Path

SHARED_INDEX = "modelrag-retrieval-units-v2"
VECTOR_DIMENSION = 1024
SMOKE_UNITS = 20_000
SMOKE_DOCUMENTS = 2_000
FULL_UNITS = 1_000_000
FULL_DOCUMENTS = 20_001
ACTIVE_BUILD_FILTER_LIMIT = 10_000


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--mode", choices=("smoke", "full"), required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--seed", type=int, default=20260226)
    parser.add_argument("--units", type=int)
    parser.add_argument("--documents", type=int)
    parser.add_argument("--active-builds", type=int)
    parser.add_argument("--stale-units", type=int)
    parser.add_argument("--include-vectors", action="store_true",
                        help="materialize vectors in JSONL; the loader can generate them from embeddingSeed")
    return parser.parse_args()


def bounded_positive(value: int | None, default: int, name: str) -> int:
    result = default if value is None else value
    if result <= 0:
        raise SystemExit(f"{name} must be positive")
    return result


def vector(seed: int, unit_id: int) -> list[float]:
    generator = random.Random((seed << 32) ^ unit_id)
    return [round(generator.uniform(-1.0, 1.0), 7) for _ in range(VECTOR_DIMENSION)]


def write_fixture(options: argparse.Namespace) -> dict:
    is_full = options.mode == "full"
    unit_count = bounded_positive(options.units, FULL_UNITS if is_full else SMOKE_UNITS, "units")
    document_count = bounded_positive(options.documents, FULL_DOCUMENTS if is_full else SMOKE_DOCUMENTS, "documents")
    build_count = bounded_positive(options.active_builds, document_count, "active-builds")
    if build_count != document_count:
        raise SystemExit("active-builds must equal documents because each active build belongs to one document")
    stale_units = max(1, options.stale_units if options.stale_units is not None else max(100, unit_count // 100))
    if is_full and (unit_count < FULL_UNITS or document_count < FULL_DOCUMENTS
                    or build_count <= ACTIVE_BUILD_FILTER_LIMIT or build_count > document_count):
        raise SystemExit(
            "full mode requires >=1,000,000 units, >=20,001 documents, and 10,001..document-count active builds")
    if not 10_000 <= unit_count <= 50_000 and not is_full:
        raise SystemExit("smoke mode must contain 10,000-50,000 retrieval units")

    output = options.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    documents_path = output / "documents.jsonl"
    units_path = output / "retrieval_units.jsonl"
    manifest_path = output / "manifest.json"

    with documents_path.open("w", encoding="utf-8", newline="\n") as stream:
        for document_id in range(1, document_count + 1):
            row = {
                "documentId": document_id,
                "datasetId": 1,
                "active": True,
                "activeVersionId": document_id,
                "activeBuildId": document_id,
            }
            stream.write(json.dumps(row, separators=(",", ":")) + "\n")

    with units_path.open("w", encoding="utf-8", newline="\n") as stream:
        for unit_id in range(1, unit_count + 1):
            document_id = (unit_id - 1) % document_count + 1
            build_id = document_id
            row = {
                "retrievalUnitId": unit_id,
                "datasetId": 1,
                "documentId": document_id,
                "documentVersionId": document_id,
                "nodeId": document_id,
                "indexBuildId": build_id,
                "active": True,
                "indexName": SHARED_INDEX,
                "embeddingDimension": VECTOR_DIMENSION,
                "content": f"deterministic retrieval unit {unit_id} document {document_id}",
            }
            if options.include_vectors:
                row["embedding"] = vector(options.seed, unit_id)
            else:
                row["embeddingSeed"] = (options.seed << 32) ^ unit_id
            stream.write(json.dumps(row, separators=(",", ":")) + "\n")
        for offset in range(stale_units):
            unit_id = unit_count + offset + 1
            document_id = (offset % document_count) + 1
            row = {
                "retrievalUnitId": unit_id,
                "datasetId": 1,
                "documentId": document_id,
                "documentVersionId": document_id,
                "nodeId": document_id,
                "indexBuildId": build_count + document_id,
                "active": False,
                "superseded": True,
                "indexName": SHARED_INDEX,
                "embeddingDimension": VECTOR_DIMENSION,
                "content": f"stale deterministic retrieval unit {unit_id}",
                "embeddingSeed": (options.seed << 32) ^ unit_id,
            }
            stream.write(json.dumps(row, separators=(",", ":")) + "\n")

    manifest = {
        "schemaVersion": 1,
        "mode": options.mode,
        "seed": options.seed,
        "activeRetrievalUnits": unit_count,
        "staleRetrievalUnits": stale_units,
        "activeDocuments": document_count,
        "activeBuildCount": build_count,
        "activeBuildFilterLimit": ACTIVE_BUILD_FILTER_LIMIT,
        "vectorDimension": VECTOR_DIMENSION,
        "vectorsMaterialized": bool(options.include_vectors),
        "sharedV2Index": SHARED_INDEX,
        "hasStaleBuilds": True,
        "files": {"documents": documents_path.name, "retrievalUnits": units_path.name},
    }
    identity = json.dumps(manifest, sort_keys=True, separators=(",", ":")).encode("utf-8")
    manifest["fixtureIdentity"] = hashlib.sha256(identity).hexdigest()
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return manifest


def main() -> int:
    options = arguments()
    manifest = write_fixture(options)
    print(json.dumps({"manifest": str((options.output / "manifest.json").resolve()),
                      "fixtureIdentity": manifest["fixtureIdentity"],
                      "activeRetrievalUnits": manifest["activeRetrievalUnits"],
                      "activeDocuments": manifest["activeDocuments"],
                      "vectorsMaterialized": manifest["vectorsMaterialized"]}, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
