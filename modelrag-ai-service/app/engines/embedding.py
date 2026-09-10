import math
from collections.abc import Sequence
from threading import RLock
from typing import Protocol, runtime_checkable

from ..config import EMBEDDING_DIMENSIONS, EMBEDDING_PROFILE
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


class SentenceTransformerEmbeddingEngine:
    """Lazy Sentence-Transformers adapter for the fixed ModelRAG embedding contract."""

    def __init__(self, model_id: str, device: str = "auto", local_files_only: bool = False,
                 max_batch_size: int = 32) -> None:
        self.model_id = model_id.strip()
        self.device = device.strip()
        self.local_files_only = local_files_only
        self.max_batch_size = max(1, min(32, max_batch_size))
        self._model = None
        self._load_error: str | None = None
        self._lock = RLock()

    def _load_model(self):
        if self._model is not None:
            return self._model
        if self._load_error is not None:
            raise EngineUnavailable("embedding runtime is unavailable")
        with self._lock:
            if self._model is not None:
                return self._model
            if self._load_error is not None:
                raise EngineUnavailable("embedding runtime is unavailable")
            if not self.model_id:
                self._load_error = "missing model id"
                raise EngineUnavailable("embedding runtime is unavailable")
            try:
                from sentence_transformers import SentenceTransformer

                kwargs = {"local_files_only": True} if self.local_files_only else {}
                model = SentenceTransformer(
                    self.model_id,
                    device=None if self.device in {"", "auto"} else self.device,
                    **kwargs,
                )
                if model.get_sentence_embedding_dimension() != EMBEDDING_DIMENSIONS:
                    raise ValueError("embedding model dimension is incompatible")
                self._model = model
                return model
            except Exception as exc:
                self._load_error = type(exc).__name__
                raise EngineUnavailable("embedding runtime is unavailable") from exc

    def embed(self, texts: Sequence[str], model: str, dimensions: int) -> Sequence[Sequence[float]]:
        if model != EMBEDDING_PROFILE or dimensions != EMBEDDING_DIMENSIONS:
            raise ValueError("embedding profile is unsupported")
        values = list(texts)
        if not values or len(values) > self.max_batch_size or any(not text.strip() for text in values):
            raise ValueError("embedding input is invalid")
        runtime_model = self._load_model()
        raw = runtime_model.encode(
            values,
            batch_size=min(len(values), self.max_batch_size),
            show_progress_bar=False,
            convert_to_numpy=True,
        )
        vectors = raw.tolist() if hasattr(raw, "tolist") else list(raw)
        if len(vectors) != len(values):
            raise ValueError("embedding runtime returned an invalid count")
        result: list[list[float]] = []
        for vector in vectors:
            converted = [float(value) for value in vector]
            if len(converted) != EMBEDDING_DIMENSIONS or any(not math.isfinite(value) for value in converted):
                raise ValueError("embedding runtime returned an invalid vector")
            result.append(converted)
        return result

    def ready(self) -> bool:
        try:
            self._load_model()
            return True
        except EngineUnavailable:
            return False
