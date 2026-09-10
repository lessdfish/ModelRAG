import math
from pathlib import Path
from threading import RLock
from typing import Any, Protocol, runtime_checkable

from ..api_models import OcrBlock, OcrResponse
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


class TesseractOcrEngine:
    """Concrete OCR adapter backed by the pytesseract Tesseract API."""

    _LANGUAGE_ALIASES = {
        "en": "eng",
        "en-us": "eng",
        "zh": "chi_sim",
        "zh-cn": "chi_sim",
        "zh-hans": "chi_sim",
    }

    def __init__(self, default_language: str = "eng", dpi: int = 200, timeout_seconds: int = 120,
                 max_blocks: int = 10_000, tesseract_cmd: str = "") -> None:
        self.default_language = default_language.strip() or "eng"
        self.dpi = max(72, min(600, dpi))
        self.timeout_seconds = max(1, min(600, timeout_seconds))
        self.max_blocks = max(1, max_blocks)
        self.tesseract_cmd = tesseract_cmd.strip()
        self._image = None
        self._pymupdf = None
        self._pytesseract = None
        self._load_error: str | None = None
        self._lock = RLock()

    def _load_dependencies(self) -> tuple[Any, Any, Any]:
        if self._image is not None and self._pymupdf is not None and self._pytesseract is not None:
            return self._image, self._pymupdf, self._pytesseract
        if self._load_error is not None:
            raise EngineUnavailable("OCR runtime is unavailable")
        with self._lock:
            if self._image is not None and self._pymupdf is not None and self._pytesseract is not None:
                return self._image, self._pymupdf, self._pytesseract
            try:
                from PIL import Image
                import pymupdf
                import pytesseract

                if self.tesseract_cmd:
                    pytesseract.pytesseract.tesseract_cmd = self.tesseract_cmd
                pytesseract.get_tesseract_version()
                self._image = Image
                self._pymupdf = pymupdf
                self._pytesseract = pytesseract
                return Image, pymupdf, pytesseract
            except Exception as exc:
                self._load_error = type(exc).__name__
                raise EngineUnavailable("OCR runtime is unavailable") from exc

    def ocr(self, source: Path, content_type: str, language_hints: list[str],
            page_number: int | None) -> OcrResponse:
        if not source.is_file():
            raise ValueError("OCR input is invalid")
        image, pymupdf, pytesseract = self._load_dependencies()
        language = self._language(language_hints)
        normalized_type = content_type.lower().split(";", 1)[0].strip()
        if normalized_type == "application/pdf" or source.suffix.lower() == ".pdf":
            page = page_number or 1
            if page < 1:
                raise ValueError("OCR page number is invalid")
            document = pymupdf.open(str(source))
            try:
                if page > len(document):
                    raise ValueError("OCR page number is invalid")
                pixmap = document[page - 1].get_pixmap(dpi=self.dpi, alpha=False)
                if pixmap.width * pixmap.height > 40_000_000:
                    raise ValueError("OCR image is too large")
                current_image = image.frombytes("RGB", [pixmap.width, pixmap.height], pixmap.samples)
                return self._recognize(current_image, language, pytesseract)
            finally:
                document.close()
        if not normalized_type.startswith("image/"):
            raise ValueError("OCR content type is unsupported")
        with image.open(source) as loaded:
            if loaded.width * loaded.height > 40_000_000:
                raise ValueError("OCR image is too large")
            current_image = loaded.convert("RGB")
            return self._recognize(current_image, language, pytesseract)

    def _recognize(self, image: Any, language: str, pytesseract: Any) -> OcrResponse:
        data = pytesseract.image_to_data(
            image,
            lang=language,
            config="--psm 3",
            output_type=pytesseract.Output.DICT,
            timeout=self.timeout_seconds,
        )
        texts = data.get("text", [])
        blocks: list[OcrBlock] = []
        for index, raw_text in enumerate(texts):
            text = str(raw_text).strip()
            if not text:
                continue
            try:
                confidence = float(data.get("conf", ["-1"] * len(texts))[index])
            except (TypeError, ValueError, IndexError):
                continue
            if confidence < 0:
                continue
            confidence = max(0.0, min(1.0, confidence / 100.0))
            bounding_box = {}
            for key in ("left", "top", "width", "height"):
                try:
                    value = float(data.get(key, [0] * len(texts))[index])
                except (TypeError, ValueError, IndexError):
                    value = 0.0
                if not math.isfinite(value):
                    value = 0.0
                bounding_box[key] = value
            blocks.append(OcrBlock(text=text, confidence=confidence, bounding_box=bounding_box))
            if len(blocks) >= self.max_blocks:
                break
        return OcrResponse(blocks=blocks)

    def _language(self, language_hints: list[str]) -> str:
        values = [hint.strip() for hint in language_hints if hint and hint.strip()]
        if not values:
            return self.default_language
        return "+".join(self._LANGUAGE_ALIASES.get(value.lower(), value) for value in values[:16])

    def ready(self) -> bool:
        try:
            self._load_dependencies()
            return True
        except EngineUnavailable:
            return False
