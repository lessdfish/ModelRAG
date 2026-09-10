from collections import Counter
from threading import Lock


class SafeMetrics:
    """Small process-local counters with operation/status tags only."""

    def __init__(self) -> None:
        self._values: Counter[tuple[str, str]] = Counter()
        self._lock = Lock()

    def record(self, operation: str, status: str) -> None:
        with self._lock:
            self._values[(operation, status)] += 1

    def snapshot(self) -> dict[str, int]:
        with self._lock:
            return {f"{operation}.{status}": count for (operation, status), count in self._values.items()}
