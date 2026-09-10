# ModelRAG AI service

`modelrag-ai-service` is a stateless FastAPI compute plane for ModelRAG. It
owns only loaded model objects, temporary request files, and safe process
metrics. Java remains the owner of storage, ACL, persistence, transactions,
index lifecycle, retries, and business state.

The service exposes:

- `POST /v1/embeddings` for the fixed `Qwen3-Embedding-0.6B` / 1024 contract;
- `POST /v1/rerank` for bounded candidate scoring;
- `POST /v1/documents/parse` for bounded document-AI parsing;
- `POST /v1/ocr` for bounded OCR blocks;
- `GET /health/live` and `GET /health/ready`.

Every `/v1` endpoint requires `Authorization: Bearer $MODELRAG_AI_AUTH_TOKEN`.
The service has no database, Redis, Elasticsearch, object-storage, JWT, or
business-state integration. Fake engines are test-only; production startup
uses `Settings.from_env()` and `build_engine_bundle()` to construct the
configured concrete engines.

## Production runtime

The checked-in `Dockerfile` is the production-capable image. It installs the
optional `runtime` extra, PyMuPDF, python-docx, Pillow, pytesseract and the
Tesseract English/Simplified Chinese language packs. Sentence-Transformers
loads the configured models on first readiness/compute use. Set
`MODELRAG_AI_AUTH_TOKEN` and provide network access or a mounted Hugging Face
model cache before calling `/health/ready`.

The runtime engine selection is explicit:

```text
MODELRAG_AI_EMBEDDING_ENGINE=sentence-transformers
MODELRAG_AI_EMBEDDING_MODEL=Qwen/Qwen3-Embedding-0.6B
MODELRAG_AI_RERANK_ENGINE=sentence-transformers
MODELRAG_AI_RERANK_MODEL=cross-encoder/ms-marco-MiniLM-L6-v2
MODELRAG_AI_DOCUMENT_AI_ENGINE=pymupdf-docx
MODELRAG_AI_OCR_ENGINE=pytesseract
```

Each capability has `*_ENABLED` and `*_REQUIRED` settings. Unknown engine
types fail production bootstrap. An enabled engine whose dependency, binary or
model cannot load is reported as `DOWN` when required and `UNAVAILABLE` when
optional. Disabled capabilities report `DISABLED` and do not make the service
unready.

The embedding identifiers are deliberately separate:

```text
physical Python model id/path: MODELRAG_AI_EMBEDDING_MODEL
logical compute profile:       Qwen3-Embedding-0.6B
V2 persisted index alias:      qwen3-v1 (when retained by Java)
```

`qwen3-v1` must always resolve to the logical profile and 1024 dimensions. A
physical model or dimension change requires a new profile/index build; it is
never silently remapped.

The real runtime smoke test is opt-in and never runs in normal unit tests:

```text
MODELRAG_RUN_REAL_RUNTIME_SMOKE=1 python -m pytest tests/test_real_runtime_smoke.py
```

If the runtime dependencies, Tesseract binary, model files or model download
are unavailable, that test reports `NOT RUN: ...` and does not claim success.

Run locally after installing the package and its test extra:

```text
uvicorn app.main:app --host 0.0.0.0 --port 18180
python -m pytest
```

For a local production-equivalent install, use `pip install ".[runtime]"`.
