import asyncio
import inspect
import json
import math
import re
import uuid
from collections.abc import Mapping, Sequence
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from fastapi import Depends, FastAPI, File, Form, Header, HTTPException, UploadFile
from pydantic import ValidationError
from starlette.responses import JSONResponse

from .api_models import (
    DocumentParseResponse,
    EmbeddingRequest,
    EmbeddingResponse,
    OcrResponse,
    ParsedNode,
    RerankRequest,
    RerankResponse,
    RerankScore,
)
from .auth import require_internal_auth
from .config import EMBEDDING_DIMENSIONS, EMBEDDING_PROFILE, Settings
from .engines.document_ai import DocumentAiEngine, UnavailableDocumentAiEngine
from .engines.embedding import EmbeddingEngine, UnavailableEmbeddingEngine
from .engines.ocr import OcrEngine, UnavailableOcrEngine
from .engines.reranker import RerankEngine, UnavailableRerankEngine
from .limits import EngineUnavailable, LimitViolation, acquire_slot, spool_upload
from .metrics import SafeMetrics


@dataclass(frozen=True)
class EngineBundle:
    embedding: EmbeddingEngine
    reranker: RerankEngine
    document_ai: DocumentAiEngine
    ocr: OcrEngine


def _coerce_engines(engines: EngineBundle | Mapping[str, Any] | None) -> EngineBundle:
    if isinstance(engines, EngineBundle):
        return engines
    if isinstance(engines, Mapping):
        return EngineBundle(
            embedding=engines.get("embedding", engines.get("embedding_engine", UnavailableEmbeddingEngine())),
            reranker=engines.get("reranker", engines.get("rerank", UnavailableRerankEngine())),
            document_ai=engines.get("document_ai", engines.get("document", UnavailableDocumentAiEngine())),
            ocr=engines.get("ocr", UnavailableOcrEngine()),
        )
    return EngineBundle(
        embedding=UnavailableEmbeddingEngine(),
        reranker=UnavailableRerankEngine(),
        document_ai=UnavailableDocumentAiEngine(),
        ocr=UnavailableOcrEngine(),
    )


def _request_id(value: str | None) -> str:
    if value and re.fullmatch(r"[A-Za-z0-9._:-]{1,128}", value):
        return value
    return str(uuid.uuid4())


def _json_response(value: Any, request_id: str) -> JSONResponse:
    if hasattr(value, "model_dump"):
        value = value.model_dump(mode="json", by_alias=True)
    return JSONResponse(content=value, headers={"X-Request-Id": request_id})


def _bad_request(message: str = "invalid compute request") -> HTTPException:
    return HTTPException(status_code=422, detail=message)


def _validate_text(value: str, max_chars: int, label: str) -> None:
    if not isinstance(value, str) or not value.strip() or len(value) > max_chars:
        raise _bad_request(f"{label} is invalid")


async def _invoke(callable_obj: Any, *args: Any) -> Any:
    if inspect.iscoroutinefunction(callable_obj):
        return await callable_obj(*args)
    result = await asyncio.to_thread(callable_obj, *args)
    if inspect.isawaitable(result):
        return await result
    return result


async def _run_compute(operation: str, semaphore: asyncio.Semaphore, metrics: SafeMetrics,
                       callable_obj: Any, *args: Any) -> Any:
    try:
        await acquire_slot(semaphore)
    except LimitViolation:
        metrics.record(operation, "saturated")
        raise HTTPException(status_code=429, detail="compute capacity is saturated")
    try:
        result = await _invoke(callable_obj, *args)
        metrics.record(operation, "ok")
        return result
    except EngineUnavailable:
        metrics.record(operation, "unavailable")
        raise HTTPException(status_code=503, detail="compute engine is unavailable")
    except LimitViolation:
        metrics.record(operation, "limited")
        raise HTTPException(status_code=429, detail="compute capacity is saturated")
    except HTTPException:
        metrics.record(operation, "rejected")
        raise
    except Exception:
        metrics.record(operation, "failed")
        raise HTTPException(status_code=502, detail="compute engine returned an invalid result")
    finally:
        semaphore.release()


def _parse_options(raw: str | None, settings: Settings) -> dict[str, Any]:
    try:
        options = json.loads(raw or "{}")
    except (TypeError, json.JSONDecodeError):
        raise _bad_request("document options are invalid")
    if not isinstance(options, dict):
        raise _bad_request("document options are invalid")
    max_pages = options.get("max_pages", settings.max_pages)
    max_chars = options.get("max_extracted_chars", settings.max_extracted_chars)
    if isinstance(max_pages, bool) or not isinstance(max_pages, int) or not 1 <= max_pages <= settings.max_pages:
        raise _bad_request("document page limit is invalid")
    if isinstance(max_chars, bool) or not isinstance(max_chars, int) or not 1 <= max_chars <= settings.max_extracted_chars:
        raise _bad_request("document character limit is invalid")
    return {
        "max_pages": max_pages,
        "max_extracted_chars": max_chars,
        "ocr": bool(options.get("ocr", True)),
        "preserve_layout": bool(options.get("preserve_layout", True)),
    }


def _validate_document(response: DocumentParseResponse, options: dict[str, Any], settings: Settings) -> None:
    if not response.metadata.parserName.strip() or not response.metadata.parserVersion.strip():
        raise ValueError("document metadata is invalid")
    if not response.nodes or len(response.nodes) > settings.max_document_nodes:
        raise ValueError("document node count is invalid")
    seen: dict[str, ParsedNode] = {}
    siblings: set[tuple[str, int]] = set()
    roots = 0
    total_chars = 0
    for node in response.nodes:
        if not node.localId.strip() or node.localId in seen:
            raise ValueError("document node ids are invalid")
        if node.parentLocalId is None:
            roots += 1
            if node.depth != 0 or node.nodeType != "DOCUMENT":
                raise ValueError("document root is invalid")
        else:
            parent = seen.get(node.parentLocalId)
            if parent is None or node.depth != parent.depth + 1:
                raise ValueError("document parent or depth is invalid")
            sibling = (node.parentLocalId, node.ordinal)
            if sibling in siblings:
                raise ValueError("document sibling ordinals are invalid")
            siblings.add(sibling)
        if (node.pageFrom is None) != (node.pageTo is None):
            raise ValueError("document page range is invalid")
        if node.pageFrom is not None and node.pageTo < node.pageFrom:
            raise ValueError("document page range is invalid")
        if node.pageTo is not None and node.pageTo > options["max_pages"]:
            raise ValueError("document page range exceeds limit")
        if (node.charStart is None) != (node.charEnd is None):
            raise ValueError("document character range is invalid")
        if node.charStart is not None and node.charEnd < node.charStart:
            raise ValueError("document character range is invalid")
        if len(node.title) > settings.max_text_chars or len(node.content) > settings.max_text_chars:
            raise ValueError("document node text exceeds limit")
        total_chars += len(node.content)
        seen[node.localId] = node
    if roots != 1:
        raise ValueError("document must have exactly one root")
    if total_chars > options["max_extracted_chars"]:
        raise ValueError("document text exceeds limit")
    for edge in response.edges:
        if edge.fromLocalId not in seen or edge.toLocalId not in seen or edge.fromLocalId == edge.toLocalId:
            raise ValueError("document edge endpoints are invalid")


def _validate_embedding(raw: Any, request: EmbeddingRequest, settings: Settings) -> EmbeddingResponse:
    raw_embeddings = raw.embeddings if isinstance(raw, EmbeddingResponse) else raw
    try:
        response = EmbeddingResponse.model_validate({
            "model": request.model,
            "dimensions": request.dimensions,
            "embeddings": raw_embeddings,
        })
    except ValidationError:
        raise HTTPException(status_code=502, detail="embedding engine returned an invalid result")
    if len(response.embeddings) != len(request.texts) or any(
        len(vector) != EMBEDDING_DIMENSIONS
        or any(not math.isfinite(value) for value in vector)
        for vector in response.embeddings
    ):
        raise HTTPException(status_code=502, detail="embedding engine returned an invalid result")
    if settings.embedding_profile != EMBEDDING_PROFILE or settings.embedding_dimensions != EMBEDDING_DIMENSIONS:
        raise HTTPException(status_code=503, detail="embedding profile is unavailable")
    return response


def _validate_rerank(raw: Any, request: RerankRequest) -> RerankResponse:
    raw_scores = raw.scores if isinstance(raw, RerankResponse) else raw
    try:
        response = RerankResponse.model_validate({"scores": raw_scores})
    except ValidationError:
        raise HTTPException(status_code=502, detail="reranker engine returned an invalid result")
    expected = [document.id for document in request.documents]
    actual = [score.id for score in response.scores]
    if len(actual) != len(expected) or set(actual) != set(expected) or len(set(actual)) != len(actual):
        raise HTTPException(status_code=502, detail="reranker engine returned an invalid result")
    if any(not math.isfinite(score.score) for score in response.scores):
        raise HTTPException(status_code=502, detail="reranker engine returned an invalid result")
    return response


def _validate_ocr(raw: Any, settings: Settings) -> OcrResponse:
    if isinstance(raw, OcrResponse):
        raw_blocks = raw.blocks
    elif isinstance(raw, Mapping):
        raw_blocks = raw.get("blocks")
    else:
        raw_blocks = raw
    try:
        response = OcrResponse.model_validate({"blocks": raw_blocks})
    except ValidationError:
        raise HTTPException(status_code=502, detail="OCR engine returned an invalid result")
    if len(response.blocks) > settings.max_ocr_blocks:
        raise HTTPException(status_code=502, detail="OCR engine returned too many blocks")
    for block in response.blocks:
        if len(block.text) > settings.max_text_chars or not math.isfinite(block.confidence):
            raise HTTPException(status_code=502, detail="OCR engine returned an invalid result")
        if block.bounding_box and any(not math.isfinite(value) for value in block.bounding_box.values()):
            raise HTTPException(status_code=502, detail="OCR engine returned an invalid result")
    return response


def create_app(settings: Settings | None = None,
               engines: EngineBundle | Mapping[str, Any] | None = None) -> FastAPI:
    settings = settings or Settings.from_env()
    bundle = _coerce_engines(engines)
    metrics = SafeMetrics()
    semaphores = {
        "embedding": asyncio.Semaphore(settings.embedding_concurrency),
        "rerank": asyncio.Semaphore(settings.rerank_concurrency),
        "document": asyncio.Semaphore(settings.document_concurrency),
        "ocr": asyncio.Semaphore(settings.ocr_concurrency),
    }
    app = FastAPI(title="ModelRAG AI Service", version="0.1.0")
    app.state.settings = settings
    app.state.engines = bundle
    app.state.metrics = metrics
    app.state.semaphores = semaphores

    async def auth_guard(authorization: str | None = Header(default=None)) -> None:
        require_internal_auth(authorization, settings)

    @app.get("/health/live")
    async def live() -> dict[str, str]:
        return {"status": "UP"}

    @app.get("/health/ready")
    async def ready() -> JSONResponse:
        engines_ready = True
        for engine in (bundle.embedding, bundle.reranker, bundle.document_ai, bundle.ocr):
            try:
                ready_check = engine.ready()
                if inspect.isawaitable(ready_check):
                    ready_check = await ready_check
                engines_ready = engines_ready and bool(ready_check)
            except Exception:
                engines_ready = False
        body = {"status": "UP" if engines_ready else "DOWN"}
        return JSONResponse(status_code=200 if engines_ready else 503, content=body)

    @app.post("/v1/embeddings", response_model=EmbeddingResponse, dependencies=[Depends(auth_guard)])
    async def embeddings(payload: EmbeddingRequest,
                         request_id: str | None = Header(default=None, alias="X-Request-Id")) -> JSONResponse:
        if payload.model != EMBEDDING_PROFILE or payload.dimensions != EMBEDDING_DIMENSIONS:
            raise _bad_request("embedding profile is unsupported")
        if not payload.texts or len(payload.texts) > 32:
            raise _bad_request("embedding batch size is invalid")
        for text in payload.texts:
            _validate_text(text, settings.max_text_chars, "embedding text")
        rid = _request_id(request_id)
        raw = await _run_compute("embeddings", semaphores["embedding"], metrics,
                                 bundle.embedding.embed, payload.texts, payload.model, payload.dimensions)
        return _json_response(_validate_embedding(raw, payload, settings), rid)

    @app.post("/v1/rerank", response_model=RerankResponse, dependencies=[Depends(auth_guard)])
    async def rerank(payload: RerankRequest,
                     request_id: str | None = Header(default=None, alias="X-Request-Id")) -> JSONResponse:
        _validate_text(payload.model, settings.max_text_chars, "reranker model")
        _validate_text(payload.query, settings.max_text_chars, "reranker query")
        if not payload.documents or len(payload.documents) > 100:
            raise _bad_request("reranker candidate count is invalid")
        identifiers: set[str] = set()
        for document in payload.documents:
            _validate_text(document.id, 256, "reranker candidate id")
            _validate_text(document.text, settings.max_text_chars, "reranker candidate text")
            if document.id in identifiers:
                raise _bad_request("reranker candidate ids must be unique")
            identifiers.add(document.id)
        rid = _request_id(request_id)
        raw = await _run_compute("rerank", semaphores["rerank"], metrics,
                                 bundle.reranker.rerank, payload.query, payload.documents, payload.model)
        return _json_response(_validate_rerank(raw, payload), rid)

    @app.post("/v1/documents/parse", response_model=DocumentParseResponse, dependencies=[Depends(auth_guard)])
    async def parse_document(file: UploadFile = File(...),
                             logical_file_name: str = Form(...),
                             options: str = Form("{}"),
                             request_id: str | None = Header(default=None, alias="X-Request-Id")) -> JSONResponse:
        _validate_text(logical_file_name, 512, "logical file name")
        parse_options = _parse_options(options, settings)
        path: Path | None = None
        try:
            try:
                path = await spool_upload(file, settings.max_upload_bytes)
            except LimitViolation:
                raise HTTPException(status_code=413, detail="uploaded file exceeds the configured limit")
            rid = _request_id(request_id)
            raw = await _run_compute("document_parse", semaphores["document"], metrics,
                                     bundle.document_ai.parse, path, logical_file_name, parse_options)
            try:
                response = DocumentParseResponse.model_validate(raw)
                _validate_document(response, parse_options, settings)
            except HTTPException:
                raise
            except (ValidationError, ValueError):
                raise HTTPException(status_code=502, detail="document engine returned an invalid structure")
            return _json_response(response, rid)
        finally:
            if path is not None:
                path.unlink(missing_ok=True)

    @app.post("/v1/ocr", response_model=OcrResponse, dependencies=[Depends(auth_guard)])
    async def ocr(file: UploadFile = File(...),
                  content_type: str | None = Form(default=None),
                  language_hints: str = Form("[]"),
                  page_number: str | None = Form(default=None),
                  request_id: str | None = Header(default=None, alias="X-Request-Id")) -> JSONResponse:
        effective_content_type = content_type or file.content_type or "application/octet-stream"
        _validate_text(effective_content_type, 128, "OCR content type")
        try:
            parsed_languages = json.loads(language_hints or "[]")
        except (TypeError, json.JSONDecodeError):
            raise _bad_request("OCR language hints are invalid")
        if not isinstance(parsed_languages, list) or any(not isinstance(value, str) for value in parsed_languages):
            raise _bad_request("OCR language hints are invalid")
        parsed_languages = [value.strip() for value in parsed_languages if value.strip()][:16]
        parsed_page: int | None = None
        if page_number not in (None, ""):
            try:
                parsed_page = int(page_number)
            except (TypeError, ValueError):
                raise _bad_request("OCR page number is invalid")
            if not 1 <= parsed_page <= settings.max_pages:
                raise _bad_request("OCR page number is invalid")
        path: Path | None = None
        try:
            try:
                path = await spool_upload(file, settings.max_upload_bytes)
            except LimitViolation:
                raise HTTPException(status_code=413, detail="uploaded file exceeds the configured limit")
            rid = _request_id(request_id)
            raw = await _run_compute("ocr", semaphores["ocr"], metrics,
                                     bundle.ocr.ocr, path, effective_content_type, parsed_languages, parsed_page)
            response = _validate_ocr(raw, settings)
            return _json_response(response, rid)
        finally:
            if path is not None:
                path.unlink(missing_ok=True)

    return app


app = create_app()
