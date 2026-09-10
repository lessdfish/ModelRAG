from dataclasses import dataclass

from .config import EMBEDDING_DIMENSIONS, EMBEDDING_PROFILE, Settings
from .engines.document_ai import (
    DocumentAiEngine,
    PyMuPdfDocxDocumentAiEngine,
    UnavailableDocumentAiEngine,
)
from .engines.embedding import (
    EmbeddingEngine,
    SentenceTransformerEmbeddingEngine,
    UnavailableEmbeddingEngine,
)
from .engines.ocr import OcrEngine, TesseractOcrEngine, UnavailableOcrEngine
from .engines.reranker import (
    RerankEngine,
    SentenceTransformerRerankEngine,
    UnavailableRerankEngine,
)


class EngineConfigurationError(ValueError):
    """Raised when production engine selection is not explicit and supported."""


@dataclass(frozen=True)
class EngineBundle:
    embedding: EmbeddingEngine
    reranker: RerankEngine
    document_ai: DocumentAiEngine
    ocr: OcrEngine


def _require_text(value: str, name: str) -> str:
    value = value.strip()
    if not value:
        raise EngineConfigurationError(f"{name} is required")
    return value


def _build_embedding(settings: Settings) -> EmbeddingEngine:
    if not settings.embedding_enabled:
        return UnavailableEmbeddingEngine()
    if settings.embedding_profile != EMBEDDING_PROFILE or settings.embedding_dimensions != EMBEDDING_DIMENSIONS:
        raise EngineConfigurationError("embedding profile must be Qwen3-Embedding-0.6B with 1024 dimensions")
    if settings.embedding_engine != "sentence-transformers":
        raise EngineConfigurationError(f"unsupported embedding engine: {settings.embedding_engine}")
    return SentenceTransformerEmbeddingEngine(
        model_id=_require_text(settings.embedding_model_id, "embedding model id"),
        device=settings.embedding_device,
        local_files_only=settings.embedding_local_files_only,
    )


def _build_reranker(settings: Settings) -> RerankEngine:
    if not settings.rerank_enabled:
        return UnavailableRerankEngine()
    if settings.rerank_engine != "sentence-transformers":
        raise EngineConfigurationError(f"unsupported reranker engine: {settings.rerank_engine}")
    return SentenceTransformerRerankEngine(
        model_id=_require_text(settings.rerank_model_id, "reranker model id"),
        device=settings.rerank_device,
        local_files_only=settings.rerank_local_files_only,
    )


def _build_ocr(settings: Settings) -> OcrEngine:
    if not settings.ocr_enabled:
        return UnavailableOcrEngine()
    if settings.ocr_engine != "pytesseract":
        raise EngineConfigurationError(f"unsupported OCR engine: {settings.ocr_engine}")
    return TesseractOcrEngine(
        default_language=_require_text(settings.ocr_default_language, "OCR default language"),
        dpi=settings.ocr_dpi,
        timeout_seconds=settings.ocr_timeout_seconds,
        max_blocks=settings.max_ocr_blocks,
        tesseract_cmd=settings.tesseract_cmd,
    )


def _build_document_ai(settings: Settings, ocr_engine: OcrEngine) -> DocumentAiEngine:
    if not settings.document_ai_enabled:
        return UnavailableDocumentAiEngine()
    if settings.document_ai_engine != "pymupdf-docx":
        raise EngineConfigurationError(f"unsupported document AI engine: {settings.document_ai_engine}")
    return PyMuPdfDocxDocumentAiEngine(
        ocr_engine=ocr_engine,
        max_pages=settings.max_pages,
        max_extracted_chars=settings.max_extracted_chars,
        max_document_nodes=settings.max_document_nodes,
    )


def build_engine_bundle(settings: Settings) -> EngineBundle:
    """Build only configured production engines; never inject test fakes."""
    ocr_engine = _build_ocr(settings)
    return EngineBundle(
        embedding=_build_embedding(settings),
        reranker=_build_reranker(settings),
        document_ai=_build_document_ai(settings, ocr_engine),
        ocr=ocr_engine,
    )
