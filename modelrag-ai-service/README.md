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
business-state integration. Model engines are injected behind protocols so
tests do not download weights. A production image should provide configured
engine implementations without changing the HTTP and security boundaries.

Run locally after installing the package and its test extra:

```text
uvicorn app.main:app --host 0.0.0.0 --port 18180
python -m pytest
```
