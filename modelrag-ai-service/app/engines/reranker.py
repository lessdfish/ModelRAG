import math
from collections.abc import Sequence
from threading import RLock
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


class SentenceTransformerRerankEngine:
    """Lazy Sentence-Transformers CrossEncoder adapter for reranking."""

    def __init__(self, model_id: str, device: str = "auto", local_files_only: bool = False,
                 max_batch_size: int = 100) -> None:
        self.model_id = model_id.strip()
        self.device = device.strip()
        self.local_files_only = local_files_only
        self.max_batch_size = max(1, min(100, max_batch_size))
        self._model = None
        self._load_error: str | None = None
        self._lock = RLock()

    def _load_model(self):
        if self._model is not None:
            return self._model
        if self._load_error is not None:
            raise EngineUnavailable("reranker runtime is unavailable")
        with self._lock:
            if self._model is not None:
                return self._model
            if self._load_error is not None:
                raise EngineUnavailable("reranker runtime is unavailable")
            if not self.model_id:
                self._load_error = "missing model id"
                raise EngineUnavailable("reranker runtime is unavailable")
            try:
                from sentence_transformers import CrossEncoder

                kwargs = {"local_files_only": True} if self.local_files_only else {}
                self._model = CrossEncoder(
                    self.model_id,
                    device=None if self.device in {"", "auto"} else self.device,
                    **kwargs,
                )
                return self._model
            except Exception as exc:
                self._load_error = type(exc).__name__
                raise EngineUnavailable("reranker runtime is unavailable") from exc

    def rerank(self, query: str, documents: Sequence[RerankDocument], model: str) -> Sequence[RerankScore]:
        values = list(documents)
        if not query.strip() or not model.strip() or not values or len(values) > self.max_batch_size:
            raise ValueError("reranker input is invalid")
        identifiers = [document.id for document in values]
        if any(not identifier.strip() for identifier in identifiers) or len(set(identifiers)) != len(identifiers):
            raise ValueError("reranker candidate ids are invalid")
        runtime_model = self._load_model()
        scores = runtime_model.predict(
            [[query, document.text] for document in values],
            batch_size=min(len(values), self.max_batch_size),
            show_progress_bar=False,
        )
        scores = scores.tolist() if hasattr(scores, "tolist") else list(scores)
        if len(scores) != len(values):
            raise ValueError("reranker runtime returned an invalid count")
        result: list[RerankScore] = []
        for document, raw_score in zip(values, scores):
            score = float(raw_score)
            if not math.isfinite(score):
                raise ValueError("reranker runtime returned an invalid score")
            result.append(RerankScore(id=document.id, score=score))
        return result

    def ready(self) -> bool:
        try:
            self._load_model()
            return True
        except EngineUnavailable:
            return False
