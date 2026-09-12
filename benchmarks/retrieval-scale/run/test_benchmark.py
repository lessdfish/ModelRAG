import importlib.util
import sys
from pathlib import Path
from types import SimpleNamespace


MODULE_PATH = Path(__file__).with_name("benchmark.py")
SPEC = importlib.util.spec_from_file_location("retrieval_benchmark", MODULE_PATH)
benchmark = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
SPEC.loader.exec_module(benchmark)


class Cursor:
    def __init__(self, values):
        self.values = iter(values)

    def __enter__(self): return self
    def __exit__(self, *_): return False
    def execute(self, *_): return None
    def fetchone(self): return next(self.values)


class Connection:
    def __init__(self, values): self.values = values
    def __enter__(self): return self
    def __exit__(self, *_): return False
    def cursor(self): return Cursor(self.values)


def manifest():
    return {"fixtureIdentity": "fixture-a", "activeRetrievalUnits": 1_000_000,
            "activeDocuments": 20_001, "activeBuildCount": 20_001,
            "staleRetrievalUnits": 10_000, "vectorDimension": 1024}


def install_psycopg(monkeypatch, values):
    monkeypatch.setitem(sys.modules, "psycopg", SimpleNamespace(connect=lambda *_args, **_kwargs: Connection(values)))


def test_manifest_million_but_postgres_smaller_is_not_runnable(monkeypatch):
    values = [(999_999, 10_000), (20_001,), (20_001,), (1_009_999, 1024, 1024, "qwen3-v1"), (1,)]
    install_psycopg(monkeypatch, values)
    monkeypatch.setattr(benchmark, "http_json", lambda *_: (200, {"count": 1_010_000}, 0))

    _, errors = benchmark.preflight("postgresql://fixture", "http://es", 1, manifest(), 1)

    assert any("activeRetrievalUnits" in error for error in errors)


def test_postgres_and_elasticsearch_fixture_identity_mismatch_is_not_runnable(monkeypatch):
    values = [(1_000_000, 10_000), (20_001,), (20_001,), (1_010_000, 1024, 1024, "qwen3-v1"), (2,)]
    install_psycopg(monkeypatch, values)
    monkeypatch.setattr(benchmark, "http_json", lambda *_: (200, {"count": 0}, 0))

    _, errors = benchmark.preflight("postgresql://fixture", "http://es", 1, manifest(), 1)

    assert any("fixtureIdentity" in error for error in errors)
    assert any("Elasticsearch fixture count" in error for error in errors)
