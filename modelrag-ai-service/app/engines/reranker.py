from collections.abc import Sequence
from typing import Protocol, runtime_checkable

from ..api_models import RerankDocument, RerankScore
from ..limits import EngineUnavailable


@runtime_checkable
class RerankEngine(Protocol):
    def rerank(self, query: str, documents: Sequence[RerankDocument], model: str) -> Sequence[RerankScore]: ...

    def ready(self) -> bool: ...


class UnavailableRerankEngine:
    def rerank(self, query: str, documents: Sequence[RerankDocument], model: str) -> Sequence[RerankScore]:
        raise EngineUnavailable("reranker engine is unavailable")

    def ready(self) -> bool:
        return False
