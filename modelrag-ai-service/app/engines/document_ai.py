from pathlib import Path
from typing import Any, Protocol, runtime_checkable

from ..api_models import DocumentParseResponse
from ..limits import EngineUnavailable


@runtime_checkable
class DocumentAiEngine(Protocol):
    def parse(self, source: Path, logical_file_name: str, options: dict[str, Any]) -> DocumentParseResponse: ...

    def ready(self) -> bool: ...


class UnavailableDocumentAiEngine:
    def parse(self, source: Path, logical_file_name: str, options: dict[str, Any]) -> DocumentParseResponse:
        raise EngineUnavailable("document AI engine is unavailable")

    def ready(self) -> bool:
        return False
