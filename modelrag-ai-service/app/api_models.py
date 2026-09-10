from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class EmbeddingRequest(StrictModel):
    model: str
    dimensions: int
    texts: list[str]


class EmbeddingResponse(StrictModel):
    model: str
    dimensions: int
    embeddings: list[list[float]]


class RerankDocument(StrictModel):
    id: str
    text: str


class RerankRequest(StrictModel):
    model: str
    query: str
    documents: list[RerankDocument]


class RerankScore(StrictModel):
    id: str
    score: float


class RerankResponse(StrictModel):
    scores: list[RerankScore]


NodeType = Literal[
    "DOCUMENT",
    "SECTION",
    "HEADING",
    "PARAGRAPH",
    "LIST",
    "LIST_ITEM",
    "TABLE",
    "TABLE_ROW",
    "FIGURE",
    "CAPTION",
    "CODE",
    "QUOTE",
    "FOOTNOTE",
]
EdgeType = Literal["REFERENCE", "ATTACHMENT", "RELATED", "SUPERSEDES", "MENTIONS"]


class DocumentParseMetadata(StrictModel):
    parserName: str
    parserVersion: str
    parserQuality: str = "MODEL"
    layoutPreserved: bool = False
    attributes: dict[str, Any] = Field(default_factory=dict)


class ParsedNode(StrictModel):
    localId: str
    parentLocalId: str | None = None
    nodeType: NodeType
    depth: int = Field(ge=0)
    ordinal: int = Field(ge=0)
    title: str = ""
    content: str = ""
    pageFrom: int | None = Field(default=None, ge=1)
    pageTo: int | None = Field(default=None, ge=1)
    charStart: int | None = Field(default=None, ge=0)
    charEnd: int | None = Field(default=None, ge=0)
    tokenCount: int = Field(default=0, ge=0)
    searchable: bool = False
    metadata: dict[str, Any] = Field(default_factory=dict)


class ParsedEdge(StrictModel):
    fromLocalId: str
    toLocalId: str
    edgeType: EdgeType
    metadata: dict[str, Any] = Field(default_factory=dict)


class DocumentParseResponse(StrictModel):
    metadata: DocumentParseMetadata
    nodes: list[ParsedNode]
    edges: list[ParsedEdge] = Field(default_factory=list)


class OcrBlock(StrictModel):
    text: str
    confidence: float = Field(ge=0, le=1)
    bounding_box: dict[str, float] | None = None


class OcrResponse(StrictModel):
    blocks: list[OcrBlock]
