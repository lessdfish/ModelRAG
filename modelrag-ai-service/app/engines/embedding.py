from collections.abc import Sequence
from typing import Protocol, runtime_checkable

from ..limits import EngineUnavailable


@runtime_checkable
class EmbeddingEngine(Protocol):
    def embed(self, texts: Sequence[str], model: str, dimensions: int) -> Sequence[Sequence[float]]: ...

    def ready(self) -> bool: ...


class UnavailableEmbeddingEngine:
    def embed(self, texts: Sequence[str], model: str, dimensions: int) -> Sequence[Sequence[float]]:
        raise EngineUnavailable("embedding engine is unavailable")

    def ready(self) -> bool:
        return False
