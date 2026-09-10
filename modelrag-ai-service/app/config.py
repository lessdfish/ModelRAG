from dataclasses import dataclass
import os


EMBEDDING_PROFILE = "Qwen3-Embedding-0.6B"
EMBEDDING_DIMENSIONS = 1024


def _int_env(name: str, default: int, minimum: int, maximum: int) -> int:
    try:
        value = int(os.getenv(name, str(default)))
    except (TypeError, ValueError):
        value = default
    return max(minimum, min(maximum, value))


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
        )
