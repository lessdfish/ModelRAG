from dataclasses import dataclass
import os


EMBEDDING_PROFILE = "Qwen3-Embedding-0.6B"
EMBEDDING_DIMENSIONS = 1024
DEFAULT_EMBEDDING_MODEL_ID = "Qwen/Qwen3-Embedding-0.6B"
DEFAULT_RERANK_MODEL_ID = "cross-encoder/ms-marco-MiniLM-L6-v2"


def _int_env(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


def _bool_env(name: str, default: bool) -> bool:
    value = os.getenv(name)
    if value is None:
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _text_env(name: str, default: str) -> str:
    return os.getenv(name, default).strip()


@dataclass(frozen=True)
class CapabilitySettings:
    enabled: bool
    required: bool
    engine: str


@dataclass(frozen=True)
class Settings:
    auth_token: str = ""
    max_upload_bytes: int = 50 * 1024 * 1024
    max_pages: int = 1_000
    max_extracted_chars: int = 5_000_000
    max_text_chars: int = 100_000
    max_ocr_blocks: int = 10_000
    max_document_nodes: int = 10_000
    embedding_concurrency: int = 4
    rerank_concurrency: int = 8
    document_concurrency: int = 2
    ocr_concurrency: int = 4
    embedding_profile: str = EMBEDDING_PROFILE
    embedding_dimensions: int = EMBEDDING_DIMENSIONS
    embedding_enabled: bool = True
    embedding_required: bool = True
    embedding_engine: str = "sentence-transformers"
    embedding_model_id: str = DEFAULT_EMBEDDING_MODEL_ID
    embedding_device: str = "auto"
    embedding_local_files_only: bool = False
    rerank_enabled: bool = True
    rerank_required: bool = True
    rerank_engine: str = "sentence-transformers"
    rerank_model_id: str = DEFAULT_RERANK_MODEL_ID
    rerank_device: str = "auto"
    rerank_local_files_only: bool = False
    document_ai_enabled: bool = True
    document_ai_required: bool = False
    document_ai_engine: str = "pymupdf-docx"
    ocr_enabled: bool = True
    ocr_required: bool = False
    ocr_engine: str = "pytesseract"
    ocr_default_language: str = "eng"
    ocr_dpi: int = 200
    ocr_timeout_seconds: int = 120
    tesseract_cmd: str = ""

    def capability(self, name: str) -> CapabilitySettings:
        values = {
            "embedding": CapabilitySettings(self.embedding_enabled, self.embedding_required, self.embedding_engine),
            "rerank": CapabilitySettings(self.rerank_enabled, self.rerank_required, self.rerank_engine),
            "document_ai": CapabilitySettings(self.document_ai_enabled, self.document_ai_required,
                                               self.document_ai_engine),
            "ocr": CapabilitySettings(self.ocr_enabled, self.ocr_required, self.ocr_engine),
        }
        try:
            return values[name]
        except KeyError as exc:
            raise ValueError(f"unknown capability: {name}") from exc


    @classmethod
    def from_env(cls) -> "Settings":
        return cls(
            auth_token=os.getenv("MODELRAG_AI_AUTH_TOKEN", "").strip(),
            max_upload_bytes=_int_env("MODELRAG_AI_MAX_UPLOAD_BYTES", 50 * 1024 * 1024, 1, 512 * 1024 * 1024),
            max_pages=_int_env("MODELRAG_DOCUMENT_AI_MAX_PAGES", 1_000, 1, 100_000),
            max_extracted_chars=_int_env("MODELRAG_DOCUMENT_AI_MAX_EXTRACTED_CHARS", 5_000_000, 1, 50_000_000),
            max_text_chars=_int_env("MODELRAG_AI_MAX_TEXT_CHARS", 100_000, 1, 1_000_000),
            max_ocr_blocks=_int_env("MODELRAG_AI_MAX_OCR_BLOCKS", 10_000, 1, 100_000),
            max_document_nodes=_int_env("MODELRAG_AI_MAX_DOCUMENT_NODES", 10_000, 1, 100_000),
            embedding_concurrency=_int_env("MODELRAG_AI_EMBEDDING_CONCURRENCY", 4, 1, 128),
            rerank_concurrency=_int_env("MODELRAG_AI_RERANK_CONCURRENCY", 8, 1, 128),
            document_concurrency=_int_env("MODELRAG_AI_DOCUMENT_CONCURRENCY", 2, 1, 64),
            ocr_concurrency=_int_env("MODELRAG_AI_OCR_CONCURRENCY", 4, 1, 128),
            embedding_enabled=_bool_env("MODELRAG_AI_EMBEDDING_ENABLED", True),
            embedding_required=_bool_env("MODELRAG_AI_EMBEDDING_REQUIRED", True),
            embedding_engine=_text_env("MODELRAG_AI_EMBEDDING_ENGINE", "sentence-transformers"),
            embedding_model_id=_text_env("MODELRAG_AI_EMBEDDING_MODEL", DEFAULT_EMBEDDING_MODEL_ID),
            embedding_device=_text_env("MODELRAG_AI_EMBEDDING_DEVICE", "auto"),
            embedding_local_files_only=_bool_env("MODELRAG_AI_EMBEDDING_LOCAL_FILES_ONLY", False),
            rerank_enabled=_bool_env("MODELRAG_AI_RERANK_ENABLED", True),
            rerank_required=_bool_env("MODELRAG_AI_RERANK_REQUIRED", True),
            rerank_engine=_text_env("MODELRAG_AI_RERANK_ENGINE", "sentence-transformers"),
            rerank_model_id=_text_env("MODELRAG_AI_RERANK_MODEL", DEFAULT_RERANK_MODEL_ID),
            rerank_device=_text_env("MODELRAG_AI_RERANK_DEVICE", "auto"),
            rerank_local_files_only=_bool_env("MODELRAG_AI_RERANK_LOCAL_FILES_ONLY", False),
            document_ai_enabled=_bool_env("MODELRAG_AI_DOCUMENT_AI_ENABLED", True),
            document_ai_required=_bool_env("MODELRAG_AI_DOCUMENT_AI_REQUIRED", False),
            document_ai_engine=_text_env("MODELRAG_AI_DOCUMENT_AI_ENGINE", "pymupdf-docx"),
            ocr_enabled=_bool_env("MODELRAG_AI_OCR_ENABLED", True),
            ocr_required=_bool_env("MODELRAG_AI_OCR_REQUIRED", False),
            ocr_engine=_text_env("MODELRAG_AI_OCR_ENGINE", "pytesseract"),
            ocr_default_language=_text_env("MODELRAG_AI_OCR_DEFAULT_LANGUAGE", "eng"),
            ocr_dpi=_int_env("MODELRAG_AI_OCR_DPI", 200, 72, 600),
            ocr_timeout_seconds=_int_env("MODELRAG_AI_OCR_TIMEOUT_SECONDS", 120, 1, 600),
            tesseract_cmd=_text_env("MODELRAG_AI_TESSERACT_CMD", ""),
        )
