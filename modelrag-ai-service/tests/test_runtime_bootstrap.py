import sys
import types

import pytest
from fastapi.testclient import TestClient

from app.config import EMBEDDING_DIMENSIONS, EMBEDDING_PROFILE, Settings
from app.engine_factory import (
    EngineConfigurationError,
    build_engine_bundle,
)
from app.engines.document_ai import PyMuPdfDocxDocumentAiEngine, UnavailableDocumentAiEngine
from app.engines.embedding import SentenceTransformerEmbeddingEngine, UnavailableEmbeddingEngine
from app.engines.ocr import TesseractOcrEngine, UnavailableOcrEngine
from app.engines.reranker import SentenceTransformerRerankEngine, UnavailableRerankEngine
from app.main import create_app


TOKEN = "test-token"


def _disabled_settings(**overrides):
    values = {
        "auth_token": TOKEN,
        "embedding_enabled": False,
        "rerank_enabled": False,
        "document_ai_enabled": False,
        "ocr_enabled": False,
    }
    values.update(overrides)
    return Settings(**values)


def _unavailable_engines():
    return {
        "embedding": UnavailableEmbeddingEngine(),
        "reranker": UnavailableRerankEngine(),
        "document_ai": UnavailableDocumentAiEngine(),
        "ocr": UnavailableOcrEngine(),
    }


def test_factory_builds_concrete_engines_without_loading_weights():
    bundle = build_engine_bundle(Settings(
        embedding_local_files_only=True,
        rerank_local_files_only=True,
    ))

    assert isinstance(bundle.embedding, SentenceTransformerEmbeddingEngine)
    assert isinstance(bundle.reranker, SentenceTransformerRerankEngine)
    assert isinstance(bundle.document_ai, PyMuPdfDocxDocumentAiEngine)
    assert isinstance(bundle.ocr, TesseractOcrEngine)


def test_unknown_engine_type_fails_bootstrap():
    with pytest.raises(EngineConfigurationError, match="unsupported embedding engine"):
        build_engine_bundle(_disabled_settings(embedding_enabled=True, embedding_engine="unknown"))


def test_required_unavailable_capability_makes_readiness_down():
    settings = _disabled_settings(embedding_enabled=True, embedding_required=True)
    response = TestClient(create_app(settings, _unavailable_engines())).get("/health/ready")

    assert response.status_code == 503
    assert response.json() == {
        "status": "DOWN",
        "capabilities": {
            "embedding": "DOWN",
            "rerank": "DISABLED",
            "document_ai": "DISABLED",
            "ocr": "DISABLED",
        },
    }


def test_disabled_capability_is_not_reported_down():
    response = TestClient(create_app(_disabled_settings(), _unavailable_engines())).get("/health/ready")

    assert response.status_code == 200
    assert response.json() == {
        "status": "UP",
        "capabilities": {
            "embedding": "DISABLED",
            "rerank": "DISABLED",
            "document_ai": "DISABLED",
            "ocr": "DISABLED",
        },
    }


def test_production_factory_never_selects_test_engines():
    bundle = build_engine_bundle(_disabled_settings(
        embedding_enabled=True,
        rerank_enabled=True,
        document_ai_enabled=True,
        ocr_enabled=True,
    ))

    assert all("test" not in type(engine).__module__ for engine in (
        bundle.embedding, bundle.reranker, bundle.document_ai, bundle.ocr))
    assert all(not type(engine).__name__.startswith("Fake") for engine in (
        bundle.embedding, bundle.reranker, bundle.document_ai, bundle.ocr))


def test_embedding_adapter_uses_physical_model_and_validates_fixed_contract(monkeypatch):
    calls = []

    class FakeModel:
        def get_sentence_embedding_dimension(self):
            return EMBEDDING_DIMENSIONS

        def encode(self, texts, **kwargs):
            calls.append((list(texts), kwargs))
            return [[1.0] + [0.0] * (EMBEDDING_DIMENSIONS - 1) for _ in texts]

    fake_module = types.SimpleNamespace(SentenceTransformer=lambda model_id, **kwargs: (
        calls.append((model_id, kwargs)) or FakeModel()))
    monkeypatch.setitem(sys.modules, "sentence_transformers", fake_module)

    engine = SentenceTransformerEmbeddingEngine("/models/qwen3-embedding")
    vectors = engine.embed(["text"], EMBEDDING_PROFILE, EMBEDDING_DIMENSIONS)

    assert len(vectors) == 1 and len(vectors[0]) == EMBEDDING_DIMENSIONS
    assert calls[0][0] == "/models/qwen3-embedding"
    with pytest.raises(ValueError):
        engine.embed(["text"], "other-profile", EMBEDDING_DIMENSIONS)


def test_reranker_adapter_preserves_candidate_ids_without_weights(monkeypatch):
    calls = []

    class FakeModel:
        def predict(self, pairs, **kwargs):
            calls.append((pairs, kwargs))
            return [0.8, 0.2]

    fake_module = types.SimpleNamespace(CrossEncoder=lambda model_id, **kwargs: (
        calls.append((model_id, kwargs)) or FakeModel()))
    monkeypatch.setitem(sys.modules, "sentence_transformers", fake_module)

    from app.api_models import RerankDocument

    engine = SentenceTransformerRerankEngine("/models/reranker")
    scores = engine.rerank("query", [RerankDocument(id="b", text="B"), RerankDocument(id="a", text="A")], "profile")

    assert [(score.id, score.score) for score in scores] == [("b", 0.8), ("a", 0.2)]
    assert calls[0][0] == "/models/reranker"


def test_engine_load_failure_is_visible_without_fake_fallback(monkeypatch):
    monkeypatch.setitem(sys.modules, "pymupdf", None)
    monkeypatch.setitem(sys.modules, "docx", None)
    monkeypatch.setitem(sys.modules, "PIL", None)

    assert not SentenceTransformerEmbeddingEngine("").ready()
    assert not SentenceTransformerRerankEngine("").ready()
    assert not PyMuPdfDocxDocumentAiEngine().ready()
    assert not TesseractOcrEngine(tesseract_cmd="C:\\missing\\tesseract.exe").ready()
