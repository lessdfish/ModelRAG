from pathlib import Path
from typing import Protocol, runtime_checkable

from ..api_models import OcrResponse
from ..limits import EngineUnavailable


@runtime_checkable
class OcrEngine(Protocol):
    def ocr(self, source: Path, content_type: str, language_hints: list[str], page_number: int | None) -> OcrResponse: ...

    def ready(self) -> bool: ...


class UnavailableOcrEngine:
    def ocr(self, source: Path, content_type: str, language_hints: list[str], page_number: int | None) -> OcrResponse:
        raise EngineUnavailable("OCR engine is unavailable")

    def ready(self) -> bool:
        return False
