import io
import os
import math

import pytest
from fastapi.testclient import TestClient

from app.config import EMBEDDING_DIMENSIONS, EMBEDDING_PROFILE, Settings
from app.engine_factory import build_engine_bundle
from app.main import create_app


def _enabled() -> bool:
    return os.getenv("MODELRAG_RUN_REAL_RUNTIME_SMOKE", "").strip().lower() in {"1", "true", "yes", "on"}


def _not_run(reason: str):
    pytest.skip(f"NOT RUN: {reason}")


@pytest.mark.skipif(not _enabled(), reason="NOT RUN: MODELRAG_RUN_REAL_RUNTIME_SMOKE is not enabled")
def test_real_runtime_smoke(tmp_path):
    token = os.getenv("MODELRAG_AI_AUTH_TOKEN", "").strip()
    if not token:
        _not_run("MODELRAG_AI_AUTH_TOKEN is not configured")
    settings = Settings.from_env()
    try:
        engines = build_engine_bundle(settings)
    except Exception as exc:
        _not_run(f"engine bootstrap unavailable: {type(exc).__name__}")
    client = TestClient(create_app(settings, engines))
    ready = client.get("/health/ready")
    if ready.status_code != 200 or any(value != "UP" for value in ready.json().get("capabilities", {}).values()):
        _not_run(f"configured runtime is unavailable: status={ready.status_code}")
    headers = {"Authorization": f"Bearer {token}"}

    embedding = client.post("/v1/embeddings", headers=headers, json={
        "model": EMBEDDING_PROFILE,
        "dimensions": EMBEDDING_DIMENSIONS,
        "texts": ["ModelRAG runtime smoke"],
    })
    assert embedding.status_code == 200
    vector = embedding.json()["embeddings"][0]
    assert len(vector) == EMBEDDING_DIMENSIONS
    assert all(math.isfinite(value) for value in vector)

    rerank = client.post("/v1/rerank", headers=headers, json={
        "model": "configured-reranker-profile",
        "query": "runtime smoke",
        "documents": [{"id": "a", "text": "runtime smoke document"}],
    })
    assert rerank.status_code == 200
    assert [score["id"] for score in rerank.json()["scores"]] == ["a"]

    import pymupdf
    pdf_path = tmp_path / "runtime-smoke.pdf"
    pdf = pymupdf.open()
    page = pdf.new_page()
    page.insert_text((72, 72), "ModelRAG runtime smoke")
    pdf.save(str(pdf_path))
    pdf.close()
    document = client.post("/v1/documents/parse", headers=headers, data={
        "logical_file_name": "runtime-smoke.pdf",
        "options": '{"max_pages": 10, "max_extracted_chars": 10000, "ocr": false}',
    }, files={"file": ("runtime-smoke.pdf", pdf_path.read_bytes(), "application/pdf")})
    assert document.status_code == 200
    assert document.json()["nodes"][0]["nodeType"] == "DOCUMENT"

    from PIL import Image, ImageDraw
    image = Image.new("RGB", (600, 160), "white")
    ImageDraw.Draw(image).text((20, 40), "ModelRAG", fill="black")
    image_bytes = io.BytesIO()
    image.save(image_bytes, format="PNG")
    ocr = client.post("/v1/ocr", headers=headers, data={
        "content_type": "image/png", "language_hints": "[\"eng\"]",
    }, files={"file": ("runtime-smoke.png", image_bytes.getvalue(), "image/png")})
    assert ocr.status_code == 200
