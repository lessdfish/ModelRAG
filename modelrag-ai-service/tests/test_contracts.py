import asyncio
from pathlib import Path

from fastapi.testclient import TestClient

from app.config import EMBEDDING_DIMENSIONS, EMBEDDING_PROFILE, Settings
from app.main import create_app


TOKEN = "test-token"


class FakeEmbedding:
    def __init__(self):
        self.calls = []

    def embed(self, texts, model, dimensions):
        self.calls.append((list(texts), model, dimensions))
        return [[1.0] + [0.0] * (dimensions - 1) for _ in texts]

    def ready(self):
        return True


class FakeReranker:
    def rerank(self, query, documents, model):
        return [{"id": document.id, "score": float(index) / 10} for index, document in enumerate(documents)]

    def ready(self):
        return True


class FakeDocumentAi:
    def __init__(self, invalid=False):
        self.paths = []
        self.invalid = invalid

    def parse(self, source, logical_file_name, options):
        self.paths.append(source)
        if self.invalid:
            return {
                "metadata": {"parserName": "fake", "parserVersion": "1"},
                "nodes": [{"localId": "root", "nodeType": "SECTION", "depth": 0, "ordinal": 0}],
                "edges": [],
            }
        return {
            "metadata": {
                "parserName": "fake-document-ai",
                "parserVersion": "1",
                "parserQuality": "MODEL",
                "layoutPreserved": True,
                "attributes": {"ocrApplied": True},
            },
            "nodes": [
                {"localId": "root", "parentLocalId": None, "nodeType": "DOCUMENT", "depth": 0,
                 "ordinal": 0, "title": logical_file_name, "content": "", "tokenCount": 0,
                 "searchable": False, "metadata": {}},
                {"localId": "section", "parentLocalId": "root", "nodeType": "SECTION", "depth": 1,
                 "ordinal": 0, "title": "Section", "content": "body", "tokenCount": 1,
                 "searchable": True, "metadata": {"page": 1}},
            ],
            "edges": [],
        }

    def ready(self):
        return True


class FakeOcr:
    def __init__(self):
        self.paths = []

    def ocr(self, source, content_type, language_hints, page_number):
        self.paths.append(source)
        return {"blocks": [{"text": "recognized", "confidence": 0.91,
                             "bounding_box": {"left": 0.1, "top": 0.2}}]}

    def ready(self):
        return True


def make_client(**kwargs):
    settings = Settings(auth_token=TOKEN, **kwargs.pop("settings", {}))
    embedding = kwargs.pop("embedding", FakeEmbedding())
    reranker = kwargs.pop("reranker", FakeReranker())
    document_ai = kwargs.pop("document_ai", FakeDocumentAi())
    ocr = kwargs.pop("ocr", FakeOcr())
    app = create_app(settings, {
        "embedding": embedding,
        "reranker": reranker,
        "document_ai": document_ai,
        "ocr": ocr,
    })
    return TestClient(app), app, embedding, document_ai, ocr


def auth_headers():
    return {"Authorization": f"Bearer {TOKEN}", "X-Request-Id": "test-request-1"}


def test_auth_and_health_endpoints():
    client, _, *_ = make_client()
    valid_payload = {
        "model": "configured-reranker-profile",
        "query": "query",
        "documents": [{"id": "0", "text": "document"}],
    }

    assert client.get("/health/live").status_code == 200
    assert client.get("/health/ready").status_code == 200
    assert client.post("/v1/rerank", json=valid_payload).status_code == 401
    assert client.post("/v1/rerank", headers={"Authorization": "Bearer wrong"}, json=valid_payload).status_code == 401
    assert client.post("/v1/rerank", headers=auth_headers(), json=valid_payload).status_code == 200


def test_embeddings_happy_path_and_profile_contract():
    client, _, embedding, *_ = make_client()

    response = client.post("/v1/embeddings", headers=auth_headers(), json={
        "model": EMBEDDING_PROFILE,
        "dimensions": EMBEDDING_DIMENSIONS,
        "texts": ["annual leave", "approval"],
    })

    assert response.status_code == 200
    assert response.headers["X-Request-Id"] == "test-request-1"
    assert response.json()["dimensions"] == EMBEDDING_DIMENSIONS
    assert len(response.json()["embeddings"][0]) == EMBEDDING_DIMENSIONS
    assert embedding.calls[0][1:] == (EMBEDDING_PROFILE, EMBEDDING_DIMENSIONS)


def test_embedding_limits_are_enforced_before_engine_call():
    client, _, embedding, *_ = make_client()
    payload = {"model": EMBEDDING_PROFILE, "dimensions": EMBEDDING_DIMENSIONS,
               "texts": ["text"] * 33}

    assert client.post("/v1/embeddings", headers=auth_headers(), json=payload).status_code == 422
    assert client.post("/v1/embeddings", headers=auth_headers(), json={
        "model": "other", "dimensions": EMBEDDING_DIMENSIONS, "texts": ["text"]}).status_code == 422
    assert not embedding.calls


def test_rerank_returns_scores_with_stable_ids_and_enforces_candidate_cap():
    client, *_ = make_client()
    response = client.post("/v1/rerank", headers=auth_headers(), json={
        "model": "configured-reranker-profile",
        "query": "annual leave",
        "documents": [{"id": "a", "text": "ten days"}, {"id": "b", "text": "sick leave"}],
    })

    assert response.status_code == 200
    assert [score["id"] for score in response.json()["scores"]] == ["a", "b"]
    too_many = [{"id": str(index), "text": "text"} for index in range(101)]
    assert client.post("/v1/rerank", headers=auth_headers(), json={
        "model": "configured-reranker-profile", "query": "q", "documents": too_many,
    }).status_code == 422


def test_document_multipart_is_bounded_and_temp_file_is_deleted():
    client, _, _, document_ai, _ = make_client()
    response = client.post("/v1/documents/parse", headers=auth_headers(), data={
        "logical_file_name": "policy.pdf",
        "options": '{"max_pages": 10, "max_extracted_chars": 1000, "ocr": true}',
    }, files={"file": ("upload.bin", b"document bytes", "application/pdf")})

    assert response.status_code == 200
    assert response.json()["nodes"][0]["nodeType"] == "DOCUMENT"
    assert document_ai.paths and all(not path.exists() for path in document_ai.paths)


def test_document_upload_and_response_limits():
    document_ai = FakeDocumentAi(invalid=True)
    client, _, _, _, _ = make_client(document_ai=document_ai,
                                     settings={"max_upload_bytes": 3})

    assert client.post("/v1/documents/parse", headers=auth_headers(), data={
        "logical_file_name": "policy.pdf", "options": "{}",
    }, files={"file": ("upload.bin", b"1234", "application/pdf")}).status_code == 413

    client, _, _, _, _ = make_client(document_ai=document_ai)
    assert client.post("/v1/documents/parse", headers=auth_headers(), data={
        "logical_file_name": "policy.pdf", "options": "{}",
    }, files={"file": ("upload.bin", b"1234", "application/pdf")}).status_code == 502
    assert document_ai.paths and all(not path.exists() for path in document_ai.paths)


def test_ocr_happy_path_limits_and_cleanup():
    client, _, _, _, ocr = make_client()
    response = client.post("/v1/ocr", headers=auth_headers(), data={
        "content_type": "image/png", "language_hints": '["eng"]', "page_number": "2",
    }, files={"file": ("page.png", b"image bytes", "image/png")})

    assert response.status_code == 200
    assert response.json()["blocks"][0]["text"] == "recognized"
    assert ocr.paths and all(not path.exists() for path in ocr.paths)


def test_saturated_operation_fails_fast():
    client, app, *_ = make_client()
    app.state.semaphores["embedding"]._value = 0

    response = client.post("/v1/embeddings", headers=auth_headers(), json={
        "model": EMBEDDING_PROFILE, "dimensions": EMBEDDING_DIMENSIONS, "texts": ["text"],
    })

    assert response.status_code == 429
