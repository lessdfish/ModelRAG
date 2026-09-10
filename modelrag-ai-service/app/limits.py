import asyncio
import os
import tempfile
from pathlib import Path

from fastapi import UploadFile


class LimitViolation(ValueError):
    pass


class EngineUnavailable(RuntimeError):
    pass


async def acquire_slot(semaphore: asyncio.Semaphore) -> None:
    """Acquire without creating a waiting queue when the operation is saturated."""
    if getattr(semaphore, "_value", 0) <= 0:
        raise LimitViolation("compute concurrency limit reached")
    await semaphore.acquire()


async def spool_upload(upload: UploadFile, max_bytes: int) -> Path:
    descriptor, raw_path = tempfile.mkstemp(prefix="modelrag-ai-", suffix=".upload")
    os.close(descriptor)
    path = Path(raw_path)
    total = 0
    try:
        with path.open("wb") as output:
            while True:
                chunk = await upload.read(64 * 1024)
                if not chunk:
                    break
                total += len(chunk)
                if total > max_bytes:
                    raise LimitViolation("uploaded file exceeds the configured limit")
                output.write(chunk)
        return path
    except Exception:
        path.unlink(missing_ok=True)
        raise
