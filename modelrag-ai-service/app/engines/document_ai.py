from pathlib import Path
from threading import RLock
from typing import Any, Protocol, runtime_checkable

from ..api_models import DocumentParseMetadata, DocumentParseResponse, ParsedNode
from ..limits import EngineUnavailable
from .ocr import OcrEngine


@runtime_checkable
class DocumentAiEngine(Protocol):
    def parse(self, source: Path, logical_file_name: str, options: dict[str, Any]) -> DocumentParseResponse: ...

    def ready(self) -> bool: ...


class UnavailableDocumentAiEngine:
    def parse(self, source: Path, logical_file_name: str, options: dict[str, Any]) -> DocumentParseResponse:
        raise EngineUnavailable("document AI engine is unavailable")

    def ready(self) -> bool:
        return False


class PyMuPdfDocxDocumentAiEngine:
    """Concrete PDF/DOCX parser using PyMuPDF and python-docx."""

    def __init__(self, ocr_engine: OcrEngine | None = None, max_pages: int = 1_000,
                 max_extracted_chars: int = 5_000_000, max_document_nodes: int = 10_000) -> None:
        self.ocr_engine = ocr_engine
        self.max_pages = max(1, max_pages)
        self.max_extracted_chars = max(1, max_extracted_chars)
        self.max_document_nodes = max(1, max_document_nodes)
        self._pymupdf = None
        self._document = None
        self._load_error: str | None = None
        self._lock = RLock()

    def _load_dependencies(self) -> tuple[Any, Any]:
        if self._pymupdf is not None and self._document is not None:
            return self._pymupdf, self._document
        if self._load_error is not None:
            raise EngineUnavailable("document AI runtime is unavailable")
        with self._lock:
            if self._pymupdf is not None and self._document is not None:
                return self._pymupdf, self._document
            try:
                import pymupdf
                from docx import Document

                self._pymupdf = pymupdf
                self._document = Document
                return pymupdf, Document
            except Exception as exc:
                self._load_error = type(exc).__name__
                raise EngineUnavailable("document AI runtime is unavailable") from exc

    def parse(self, source: Path, logical_file_name: str, options: dict[str, Any]) -> DocumentParseResponse:
        if not source.is_file() or not logical_file_name.strip():
            raise ValueError("document input is invalid")
        self._load_dependencies()
        max_pages = self._bounded_option(options, "max_pages", self.max_pages)
        max_chars = self._bounded_option(options, "max_extracted_chars", self.max_extracted_chars)
        suffix = Path(logical_file_name).suffix.lower()
        if suffix == ".pdf":
            return self._parse_pdf(source, logical_file_name, options, max_pages, max_chars)
        if suffix == ".docx":
            return self._parse_docx(source, logical_file_name, max_chars)
        raise ValueError("unsupported document type")

    def ready(self) -> bool:
        try:
            self._load_dependencies()
            return True
        except EngineUnavailable:
            return False

    @staticmethod
    def _bounded_option(options: dict[str, Any], name: str, maximum: int) -> int:
        value = options.get(name, maximum)
        if isinstance(value, bool) or not isinstance(value, int) or not 1 <= value <= maximum:
            raise ValueError("document limit is invalid")
        return value

    def _root(self, logical_file_name: str) -> ParsedNode:
        return ParsedNode(
            localId="root",
            nodeType="DOCUMENT",
            depth=0,
            ordinal=0,
            title=logical_file_name,
            searchable=False,
        )

    def _append(self, nodes: list[ParsedNode], node: ParsedNode, total_chars: int, max_chars: int) -> int:
        if len(nodes) >= self.max_document_nodes:
            raise ValueError("document node limit exceeded")
        if total_chars + len(node.content) > max_chars:
            raise ValueError("document character limit exceeded")
        nodes.append(node)
        return total_chars + len(node.content)

    def _parse_pdf(self, source: Path, logical_file_name: str, options: dict[str, Any],
                   max_pages: int, max_chars: int) -> DocumentParseResponse:
        pymupdf, _ = self._load_dependencies()
        nodes = [self._root(logical_file_name)]
        total_chars = 0
        ocr_applied = False
        page_count = 0
        try:
            document = pymupdf.open(str(source))
            try:
                page_count = len(document)
                if page_count > max_pages:
                    raise ValueError("document page limit exceeded")
                for page_index in range(page_count):
                    page_number = page_index + 1
                    page = document[page_index]
                    blocks = page.get_text("blocks", sort=True)
                    page_has_text = False
                    ordinal = 0
                    for block in blocks:
                        if len(block) < 5:
                            continue
                        content = str(block[4]).strip()
                        if not content:
                            continue
                        page_has_text = True
                        metadata = {
                            "page": page_number,
                            "bbox": {"x0": float(block[0]), "y0": float(block[1]),
                                     "x1": float(block[2]), "y1": float(block[3])},
                        }
                        node = ParsedNode(
                            localId=f"page-{page_number}-block-{ordinal}",
                            parentLocalId="root",
                            nodeType="PARAGRAPH",
                            depth=1,
                            ordinal=len(nodes) - 1,
                            content=content,
                            tokenCount=len(content.split()),
                            searchable=True,
                            pageFrom=page_number,
                            pageTo=page_number,
                            metadata=metadata,
                        )
                        total_chars = self._append(nodes, node, total_chars, max_chars)
                        ordinal += 1
                    if not page_has_text and options.get("ocr", True):
                        if self.ocr_engine is None or not self.ocr_engine.ready():
                            raise EngineUnavailable("OCR runtime is unavailable")
                        language_hints = options.get("language_hints", [])
                        ocr_result = self.ocr_engine.ocr(source, "application/pdf", list(language_hints), page_number)
                        ocr_text = "\n".join(block.text.strip() for block in ocr_result.blocks if block.text.strip())
                        if ocr_text:
                            node = ParsedNode(
                                localId=f"page-{page_number}-ocr",
                                parentLocalId="root",
                                nodeType="PARAGRAPH",
                                depth=1,
                                ordinal=len(nodes) - 1,
                                content=ocr_text,
                                tokenCount=len(ocr_text.split()),
                                searchable=True,
                                pageFrom=page_number,
                                pageTo=page_number,
                                metadata={"page": page_number, "ocr": True},
                            )
                            total_chars = self._append(nodes, node, total_chars, max_chars)
                            ocr_applied = True
            finally:
                document.close()
        except EngineUnavailable:
            raise
        except Exception as exc:
            raise ValueError("PDF parsing failed") from exc
        return DocumentParseResponse(
            metadata=DocumentParseMetadata(
                parserName="python-pymupdf",
                parserVersion="1",
                parserQuality="HEURISTIC",
                layoutPreserved=True,
                attributes={"pageCount": page_count, "ocrApplied": ocr_applied},
            ),
            nodes=nodes,
            edges=[],
        )

    def _parse_docx(self, source: Path, logical_file_name: str, max_chars: int) -> DocumentParseResponse:
        _, document_constructor = self._load_dependencies()
        nodes = [self._root(logical_file_name)]
        total_chars = 0
        table_count = 0
        try:
            document = document_constructor(str(source))
            blocks = document.iter_inner_content() if hasattr(document, "iter_inner_content") else document.paragraphs
            for block in blocks:
                if hasattr(block, "text"):
                    content = str(block.text).strip()
                    if not content:
                        continue
                    style = str(getattr(getattr(block, "style", None), "name", ""))
                    style_lower = style.lower()
                    node_type = "HEADING" if "heading" in style_lower else (
                        "LIST_ITEM" if "list" in style_lower else "PARAGRAPH")
                    node = ParsedNode(
                        localId=f"block-{len(nodes) - 1}",
                        parentLocalId="root",
                        nodeType=node_type,
                        depth=1,
                        ordinal=len(nodes) - 1,
                        title=content if node_type == "HEADING" else "",
                        content=content,
                        tokenCount=len(content.split()),
                        searchable=True,
                        metadata={"style": style} if style else {},
                    )
                    total_chars = self._append(nodes, node, total_chars, max_chars)
                    continue
                rows = getattr(block, "rows", None)
                if rows is None:
                    continue
                table_id = f"table-{table_count}"
                table = ParsedNode(
                    localId=table_id,
                    parentLocalId="root",
                    nodeType="TABLE",
                    depth=1,
                    ordinal=len(nodes) - 1,
                    title=f"Table {table_count + 1}",
                    searchable=False,
                    metadata={"columns": len(block.columns)},
                )
                total_chars = self._append(nodes, table, total_chars, max_chars)
                for row_index, row in enumerate(rows):
                    content = " | ".join(str(cell.text).strip() for cell in row.cells).strip()
                    row_node = ParsedNode(
                        localId=f"{table_id}-row-{row_index}",
                        parentLocalId=table_id,
                        nodeType="TABLE_ROW",
                        depth=2,
                        ordinal=row_index,
                        content=content,
                        tokenCount=len(content.split()),
                        searchable=bool(content),
                        metadata={"row": row_index},
                    )
                    total_chars = self._append(nodes, row_node, total_chars, max_chars)
                table_count += 1
        except ValueError:
            raise
        except Exception as exc:
            raise ValueError("DOCX parsing failed") from exc
        return DocumentParseResponse(
            metadata=DocumentParseMetadata(
                parserName="python-docx",
                parserVersion="1",
                parserQuality="HEURISTIC",
                layoutPreserved=False,
                attributes={"tableCount": table_count},
            ),
            nodes=nodes,
            edges=[],
        )
