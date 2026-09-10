# ModelRAG 2.0 Refactor Plan

> Status: architecture source of truth  
> Target runtime: Java 21
> Migration strategy: incremental, no big-bang rewrite  
> Legacy Flyway migrations: `V1-V53` are immutable

---

## 1. Why ModelRAG 2.0 Exists

The current ModelRAG already contains many production-oriented capabilities:

- modular backend structure
- dataset/document lifecycle
- pgvector semantic retrieval
- Elasticsearch/BM25 retrieval
- hybrid fusion
- reranking
- MMR
- parent/neighbor expansion
- index versioning
- outbox
- ACL
- audit
- SSE replay
- idempotency
- Agent execution persistence
- tool approval
- cancellation
- tracing
- model routing

The main architectural limitation is not the lack of engineering capability. It is that the knowledge model and retrieval runtime are still largely **chunk-centric**.

The current mental model is approximately:

```text
Document
  ↓
Chunk
  ↓
Embedding / BM25
  ↓
TopK
  ↓
Parent / Neighbor Expansion
  ↓
LLM
```

This works for point-fact retrieval, but becomes fragile for:

- cross-section questions
- cross-document questions
- multi-hop reasoning
- version-sensitive documents
- references and appendices
- tables and structured documents
- questions requiring evidence completeness
- large datasets where query cost must not grow with dataset size

The target is:

```text
Document-native knowledge model
        +
Evidence-centric retrieval
        +
Agentic retrieval
        +
Durable Agent runtime
        +
Java control plane
        +
Python AI compute plane
```

---

# 2. Target Architecture

```text
                              Client
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────┐
│                         Java Control Plane                          │
│                                                                     │
│  API / Auth / ACL / RateLimit / Idempotency                         │
│                     │                                               │
│              RequestModeRouter                                      │
│             ┌───────┼─────────────┐                                 │
│             ▼       ▼             ▼                                 │
│       DIRECT_RAG  AGENTIC_RAG   TOOL_AGENT                          │
│             │       │             │                                 │
│             │       ▼             ▼                                 │
│             │   Agent Runtime ─── Tool Gateway ─── Business System  │
│             │       │                                               │
│             └───────┼───────────────────────────────────────────────┤
│                     ▼                                               │
│              Retrieval Runtime                                      │
│                     │                                               │
│        ┌────────────┼──────────────┐                                │
│        ▼            ▼              ▼                                │
│    Semantic      Lexical       Navigation                           │
│    Retrieval     Retrieval      Retrieval                           │
│    pgvector      Elasticsearch  Document Tree                       │
│        └────────────┬──────────────┘                                │
│                     ▼                                               │
│               Evidence Store                                        │
│                     │                                               │
│               Answer Synthesizer                                    │
│                                                                     │
│  Document Domain ─ Index Lifecycle ─ Outbox ─ Audit ─ SSE ─ Metrics │
│                                                                     │
│                   PostgreSQL = Source of Truth                      │
└───────────────────────────┬─────────────────────────────────────────┘
                            │ HTTP / gRPC
                            ▼
┌─────────────────────────────────────────────────────────────────────┐
│                       Python AI Compute Plane                       │
│                                                                     │
│  Embedding        Reranker        OCR        Document AI             │
│                                                                     │
│  Local Model Serving / GPU Inference                                │
│                                                                     │
│  NO ACL / NO Transaction / NO Business State / NO Agent State       │
└─────────────────────────────────────────────────────────────────────┘
```

Core rule:

```text
Java = orchestration + state + policy + business
Python = model computation
```

External SaaS LLM providers such as OpenAI, Claude, Gemini, or Qwen API do not need to be proxied through Python. Java may continue to access them through Spring AI or provider SDKs.

---

# 3. Core Refactor Goals

| Current | ModelRAG 2.0 |
|---|---|
| `Chunk` is the central knowledge object | `DocumentNode` is the structural knowledge object |
| chunk is structure + retrieval + embedding + citation unit | structure and retrieval projection are separated |
| `knowledge_lookup -> qa.answer()` | Agent manipulates candidates/nodes/evidence directly |
| Agent state mostly lives in local Java variables | Agent state is explicit and checkpointed |
| embedding/rerank logic may be tightly coupled to Java implementation | Java ports call remote/local AI compute services |
| `KnowledgeStore` owns too many responsibilities | repositories are split by bounded domain |
| retrieval traces are stage JSON blobs | retrieval actions and evidence are first-class records |

---

# 4. Domain Model

## 4.1 Document

`Document` represents a logical document.

Example:

```text
员工休假制度
```

It does not represent a specific content revision.

Suggested fields:

```text
id
datasetId
fileName
fileType
status
activeVersionId
activeIndexBuildId
createTime
updateTime
```

---

## 4.2 DocumentVersion

`DocumentVersion` represents an immutable content snapshot.

Example:

```text
员工休假制度 v3
```

Suggested fields:

```text
id
documentId
versionNo
sourceObjectKey
artifactObjectKey
contentHash
parserName
parserVersion
parseStatus
metadata
createTime
readyTime
```

Important rule:

```text
Document Version != Index Build Version
```

---

## 4.3 DocumentNode

`DocumentNode` is the structural knowledge object.

Example:

```text
Document
├── Section
│   ├── Paragraph
│   ├── Paragraph
│   └── Table
└── Appendix
```

Initial node types:

```text
DOCUMENT
SECTION
HEADING
PARAGRAPH
LIST
LIST_ITEM
TABLE
TABLE_ROW
FIGURE
CAPTION
CODE
QUOTE
FOOTNOTE
```

Suggested fields:

```text
id
datasetId
documentId
documentVersionId
parentId
nodeType
depth
ordinal
title
content
contentHash
pageFrom
pageTo
charStart
charEnd
tokenCount
searchable
metadata
createTime
```

---

## 4.4 NodeEdge

Tree hierarchy should use `parent_id`.

Do not duplicate `PARENT`/`CHILD` in an edge table.

Use `NodeEdge` only for non-tree relations such as:

```text
REFERENCE
ATTACHMENT
RELATED
SUPERSEDES
MENTIONS
```

Suggested fields:

```text
id
fromNodeId
toNodeId
edgeType
metadata
createTime
```

---

## 4.5 RetrievalUnit

`RetrievalUnit` is a retrieval projection, not a source-of-truth knowledge object.

This is one of the most important changes in ModelRAG 2.0.

A `DocumentNode` may produce multiple retrieval units.

Examples:

```text
PARAGRAPH
WINDOW
SECTION
SECTION_SUMMARY
TABLE
TITLE_PATH
```

Suggested fields:

```text
id
datasetId
documentId
documentVersionId
nodeId
indexBuildId
unitType
ordinal
titlePath
content
tokenCount
metadata
createTime
```

V2 retrieval-unit activity is derived from the owning document pointers rather
than stored on each unit:

```text
u.index_build_id = d.active_index_build_id
AND
u.document_version_id = d.active_version_id
```

Build activation must therefore update build/document state and pointers only;
it must not mass-update retrieval units.

This separates:

```text
knowledge structure
```

from:

```text
retrieval strategy
```

Changing chunk size, embedding model, retrieval window, or section-summary strategy no longer destroys the original document structure.

---

## 4.6 Evidence

Evidence is a runtime object selected from retrieval results.

Suggested runtime model:

```text
Evidence {
    evidenceId
    documentId
    documentVersionId
    nodeId
    retrievalUnitId
    documentName
    titlePath
    page
    excerpt
    score
    channel
    locator
}
```

Final answer generation should consume an `EvidenceSet`.

---

# 5. Database Migration Plan

Never edit `V1-V53`.

Start ModelRAG 2.0 schema changes from `V54`.

---

## 5.1 V54 - kb_document_version

Create:

```text
kb_document_version
```

Columns:

| Column | Type | Purpose |
|---|---|---|
| `id` | BIGSERIAL PK | version id |
| `document_id` | BIGINT FK | logical document |
| `version_no` | BIGINT | content revision |
| `source_object_key` | VARCHAR(1000) | original file |
| `artifact_object_key` | VARCHAR(1000) | parsed artifact |
| `content_hash` | VARCHAR(64) | content hash |
| `parser_name` | VARCHAR(100) | parser |
| `parser_version` | VARCHAR(80) | parser version |
| `parse_status` | VARCHAR(30) | parse lifecycle |
| `metadata` | JSONB | metadata |
| `create_time` | TIMESTAMPTZ | created |
| `ready_time` | TIMESTAMPTZ | ready |

Unique:

```text
(document_id, version_no)
```

Add to `kb_document`:

```text
active_version_id
active_index_build_id
```

Long term, `source_object_key`, `artifact_object_key`, `content_hash` belong to `DocumentVersion`, not logical `Document`.

---

## 5.2 V55 - kb_document_node

Create:

```text
kb_document_node
```

Recommended indexes:

```text
(document_version_id, parent_id, ordinal)

(document_version_id, node_type)

(document_id, document_version_id)

(dataset_id, document_version_id)
```

Neighbor lookup should be bounded by:

```text
document_version_id
parent_id
ordinal range
```

---

## 5.3 V56 - kb_node_edge

Create:

```text
kb_node_edge
```

Recommended unique key:

```text
(from_node_id, to_node_id, edge_type)
```

---

## 5.4 V57 - kb_retrieval_unit

Create:

```text
kb_retrieval_unit
```

Recommended indexes:

```text
(index_build_id, id)

(document_version_id, node_id)

(dataset_id, document_id, index_build_id)

(unit_type)
```

---

## 5.5 V58 - kb_vector_embedding

Create:

```text
kb_vector_embedding
```

Suggested fields:

```text
id
retrieval_unit_id
dataset_id
document_id
index_build_id
embedding_profile
embedding vector(1024)
create_time
```

Unique:

```text
(retrieval_unit_id, embedding_profile)
```

Create HNSW index on:

```text
embedding vector_cosine_ops
```

Do not overwrite embeddings in-place when the embedding profile changes.

Allow multiple embedding profiles to coexist during migration or evaluation.

---

## 5.6 V59 - kb_index_build

Create:

```text
kb_index_build
```

Suggested fields:

```text
id
datasetId
documentId
documentVersionId
buildVersion
state
embeddingProfile
rerankProfile
vectorCount
lexicalCount
nodeCount
unitCount
errorMsg
startTime
readyTime
activeTime
```

Lifecycle:

```text
CREATED
  ↓
PARSING
  ↓
STRUCTURE_READY
  ↓
UNIT_BUILDING
  ↓
VECTOR_BUILDING
  ↓
LEXICAL_SYNCING
  ↓
VERIFYING
  ↓
READY
  ↓
ACTIVE
```

Any stage may become:

```text
FAILED
```

Activation rule:

```text
only READY builds may become ACTIVE
```

If a new build fails, the previous active build must continue serving traffic.

---

## 5.7 V60 - V2 Retrieval Projection Outbox

G4 adds a dedicated asynchronous lexical projection outbox:

```text
kb_retrieval_projection_outbox
```

Each row belongs to one `IndexBuild` and one `RetrievalUnit`. The outbox owns
lease, retry, dead-letter, and idempotency state. Its payload is the V2
Elasticsearch document; it must never overload the legacy chunk-shaped outbox
or `chunk_id`.

Required indexes are:

```text
(status, next_retry_at, id)
(index_build_id, status)
(document_id, index_build_id)
(retrieval_unit_id)
```

V2 projection writes one shared Elasticsearch index:

```text
modelrag-retrieval-units-v2
```

There is no V2 read alias or query path in this phase. The existing
`modelrag-chunks-active` alias and V1 indexing lifecycle remain unchanged.

---

## 5.8 V61 - Retrieval Trace Header and Actions

Keep `kb_retrieval_trace` as the run header.

Add fields such as:

```text
mode
user_id
execution_id
status
total_actions
total_evidence
token_budget
started_at
completed_at
```

Create:

```text
kb_retrieval_action
```

Fields:

```text
id
trace_id
step_no
action_type
request_json
result_summary
latency_ms
degraded
create_time
```

Action types:

```text
SEMANTIC_SEARCH
LEXICAL_SEARCH
HYBRID_SEARCH
FIND
OPEN
READ_PARENT
READ_CHILDREN
READ_NEIGHBORS
FOLLOW_REFERENCE
RERANK
```

---

## 5.9 V62 - kb_retrieval_evidence

Create:

```text
kb_retrieval_evidence
```

Suggested fields:

```text
id
trace_id
action_id
dataset_id
document_id
document_version_id
node_id
retrieval_unit_id
channel
score
rank
selected
excerpt
locator JSONB
create_time
```

Example locator:

```json
{
  "document": "员工休假制度.pdf",
  "titlePath": "第三章 > 3.2 请假审批",
  "page": 12,
  "paragraph": 4
}
```

---

## 5.10 V63 - Resource ACL

Current dataset ACL should be retained.

Add resource-level ACL when needed:

```text
kb_resource_acl
```

Suggested fields:

```text
resource_type
resource_id
principal_type
principal_id
permission
```

Examples:

```text
DOCUMENT / 123 / USER / lyp / READ

DOCUMENT / 123 / ROLE / HR / READ

DATASET / 5 / ROLE / ADMIN / WRITE
```

Do not duplicate ACL rows for every node.

Nodes inherit document/dataset authorization.

---

## 5.11 V64 - Agent Checkpoint

Extend:

```text
kb_agent_execution
```

with fields such as:

```text
mode
goal
current_step
max_steps
deadline_at
state_version
budget_json
result_json
last_checkpoint_seq
lease_owner
lease_until
error_code
error_msg
finished_at
```

Create:

```text
kb_agent_checkpoint
```

Fields:

```text
id
execution_id
checkpoint_seq
state_json
state_version
status
create_time
```

Unique:

```text
(execution_id, checkpoint_seq)
```

Trace and checkpoint must remain separate:

```text
kb_agent_step_trace = observability
kb_agent_checkpoint = recovery state
```

---

# 6. Java Module Strategy

Do not rename existing Maven modules during early migration.

Initial target:

```text
ModelRAG
├── modelrag-api
├── modelrag-common
├── modelrag-knowledge
├── modelrag-indexing
├── modelrag-search
├── modelrag-qa
├── modelrag-agent
├── modelrag-tool-gateway        NEW
├── modelrag-inference-client    NEW
├── modelrag-server
└── modelrag-client
```

Later, after stabilization:

```text
modelrag-search -> modelrag-retrieval
modelrag-agent  -> modelrag-agent-runtime
```

Do not combine module renaming with deep domain refactoring.

---

# 7. modelrag-api Refactor

This module should become the stable port/contract layer.

It must not depend on:

```text
JdbcTemplate
Elasticsearch client
Python implementation
Spring MVC details
```

Keep or evolve:

```text
KnowledgeAssistant
ConversationRepository
ConversationContextBuilder
LongTermMemoryStore
TextEmbeddingProvider
ModelInvocationCanceller
SecretProtector
UserModelProvider
```

Unify reranking into a single port:

```text
RerankPort
```

Suggested retrieval contracts:

```text
RetrievalGateway
RetrievalRequest
RetrievalResult
RetrievalCandidate
Evidence
EvidenceSet
DocumentLocator
RetrievalBudget
AccessScope
```

---

# 8. modelrag-knowledge Refactor

Current long-term model:

```text
Dataset
Document
Chunk
```

Target:

```text
Dataset
Document
DocumentVersion
DocumentNode
NodeEdge
RetrievalUnit
```

## 8.1 Chunk

Mark as legacy / deprecated after V2 path is available.

Do not immediately delete.

Use only for:

```text
legacy retrieval
old traces
old evaluation
dual-write compatibility
```

Delete only in the explicit cleanup phase.

---

## 8.2 Document

Convert into a logical document entity.

Move version-specific content fields to `DocumentVersion`.

---

## 8.3 New classes

Add:

```text
DocumentVersion
DocumentNode
NodeType
NodeEdge
NodeEdgeType
RetrievalUnit
IndexBuild
```

---

# 9. Split KnowledgeStore

Current `KnowledgeStore` owns too many concerns.

Target repositories:

```text
DatasetRepository
DocumentRepository
DocumentVersionRepository
DocumentStructureRepository
RetrievalUnitRepository
IndexBuildRepository
```

Responsibilities:

## DatasetRepository

```text
create
find
update
delete
routeDatasets
```

## DocumentRepository

```text
create
find
list
softDelete
activateVersion
activateBuild
```

## DocumentVersionRepository

```text
createVersion
findVersion
findActiveVersion
markParsed
```

## DocumentStructureRepository

```text
saveNodes
findNode
findNodesByIds
findParent
findChildren
findNeighbors
findEdges
findReferences
```

## RetrievalUnitRepository

```text
saveBatch
findByIds
findByNode
deleteByBuild
```

## IndexBuildRepository

```text
createBuild
transition
markReady
activate
fail
```

`PostgresKnowledgeStore` should eventually be replaced by:

```text
JdbcDatasetRepository
JdbcDocumentRepository
JdbcDocumentVersionRepository
JdbcDocumentStructureRepository
JdbcRetrievalUnitRepository
JdbcIndexBuildRepository
```

---

# 10. Immediate Retrieval P0

The current retrieval context expansion must not call a method that loads every chunk in a dataset.

Current anti-pattern:

```text
TopK
↓
store.chunks(datasetId)
↓
build JVM map
↓
find parent / neighbors
```

Target:

```text
TopK
↓
find needed chunks/nodes only
↓
bounded parent/neighbor query
↓
evidence expansion
```

Complexity target:

```text
O(N) dataset transfer/memory
```

becomes approximately:

```text
O(K)
```

or:

```text
O(K log N)
```

where `K` is proportional to selected evidence and expansion radius.

This must be completed before deeper ModelRAG 2.0 work.

---

# 11. Document Parsing Refactor

Current parser contract:

```text
Path -> String
```

Target:

```text
Path -> ParsedDocument
```

Add:

```text
ParsedDocument
ParsedNode
ParsedEdge
ParsedPage
DocumentParseMetadata
```

Example:

```text
ParsedDocument
├── metadata
├── root
│   ├── section
│   │   ├── paragraph
│   │   └── table
│   └── section
└── edges
```

Local Java parsers may handle:

```text
TXT
Markdown
```

Complex files should eventually use:

```text
RemoteDocumentAiParser
```

for:

```text
PDF
DOCX
scanned PDF
images
complex tables
```

---

# 12. RecursiveCharSplitter Role Change

Do not delete it.

Current role:

```text
document -> chunk -> knowledge model
```

Target role:

```text
DocumentNode
↓
RetrievalUnitBuilder
↓
if node is too large
↓
RecursiveCharSplitter
```

It becomes a retrieval-projection utility, not the knowledge-structure generator.

---

# 13. IndexingPipeline Refactor

Current `IndexingPipeline` owns too many responsibilities.

Target:

```text
IndexBuildCoordinator
├── DocumentParseStage
├── StructurePersistStage
├── RetrievalUnitBuildStage
├── EmbeddingStage
├── VectorProjectionStage
├── LexicalProjectionStage
├── IndexVerificationStage
└── IndexActivationStage
```

`IndexBuildCoordinator` should orchestrate stages.

It should not implement parsing, embedding internals, ES synchronization, node construction, and activation logic directly.

---

# 14. Transaction Boundaries

Never keep remote AI/model calls inside long database transactions.

Bad:

```text
@Transactional
parse
HTTP embedding
ES
Python OCR
LLM
commit
```

Target:

```text
TX1: create build
commit

remote parse

TX2: persist node batch
commit

remote embedding

TX3: write units/vectors/outbox
commit

async ES projection

TX4: verify + activate
commit
```

This prevents remote latency from holding database locks/connections.

---

# 15. Embedding and AI Compute

`EmbeddingService` remains in Java and owns:

```text
batching
cache
timeout
retry
metrics
provider selection
```

Model computation is behind:

```text
TextEmbeddingProvider
```

Target path:

```text
EmbeddingService
↓
TextEmbeddingProvider
↓
RemoteEmbeddingProvider
↓
Python /v1/embeddings
```

Recommended cache key:

```text
SHA256(embeddingProfile + contentHash)
```

---

# 16. modelrag-inference-client

Add a Java module:

```text
modelrag-inference-client
```

Suggested classes:

```text
AiServiceClient
RemoteEmbeddingProvider
RemoteRerankProvider
RemoteDocumentAiClient
RemoteOcrClient
RemoteInferenceClient
AiServiceHealthIndicator
AiRequestMetrics
```

DTOs:

```text
EmbeddingRequest
EmbeddingResponse
RerankRequest
RerankResponse
DocumentParseRequest
DocumentParseResponse
OcrRequest
OcrResponse
```

All clients must support:

```text
timeout
retry
circuit breaker
traceId
requestId
metrics
```

Initial transport:

```text
HTTP
```

Possible future transport:

```text
gRPC
```

---

# 17. Python AI Compute Plane

Initial APIs:

```text
POST /v1/embeddings
POST /v1/rerank
POST /v1/documents/parse
POST /v1/ocr
GET  /health/live
GET  /health/ready
```

Python must not directly access ModelRAG business tables.

Java persists all state.

Python should be replaceable without changing Java business logic.

---

# 18. Semantic Retrieval V2

Current vector storage centered on `kb_chunk`.

Target:

```text
PostgresSemanticSearchAdapter
```

Search source:

```text
kb_vector_embedding
JOIN kb_retrieval_unit
JOIN kb_document
JOIN kb_index_build
```

Filters must include:

```text
dataset
active build
authorization scope
embedding profile
```

Return:

```text
RetrievalCandidate
```

not:

```text
SearchResult(chunkId,...)
```

G5 implementation details:

```text
kb_vector_embedding
    JOIN kb_retrieval_unit
    JOIN kb_document
    JOIN kb_dataset
    JOIN kb_index_build
```

The semantic adapter executes this as a bounded, read-only pgvector query. It
requires the requested embedding profile, excludes soft-deleted datasets and
documents, requires `d.active_version_id = u.document_version_id`, requires
`d.active_index_build_id = u.index_build_id`, and requires
`kb_index_build.state = 'ACTIVE'`. It also sets
`hnsw.iterative_scan = 'relaxed_order'` locally for the transaction. The
legacy `PostgresVectorStore` continues to read `kb_chunk` and remains the V1
production channel.

Authorization scope is still a cutover requirement. G5's internal shadow
adapter preserves the existing dataset/active-version visibility rules but
does not replace the established authorization policy or expose a V2 user
endpoint.

Suggested candidate:

```text
unitId
nodeId
documentId
documentVersionId
score
channel
locator
```

---

# 19. Lexical Retrieval V2

Elasticsearch index becomes RetrievalUnit-centric.

Suggested fields:

```text
unitId
nodeId
datasetId
documentId
documentVersionId
indexBuildId
nodeType
title
titlePath
content
pageFrom
pageTo
aclLabels
metadata
```

Search boosts may include:

```text
title^4
titlePath^3
content
```

This improves section headings, policy numbers, and exact terminology.

G5 queries the shared index `modelrag-retrieval-units-v2` without changing the
legacy `ElasticsearchBm25Search` / `modelrag-chunks-active` path. Before the
query, `ActiveBuildScopeResolver` obtains active build IDs from PostgreSQL by
joining document active pointers with `kb_index_build.state = 'ACTIVE'` and
matching the active document version. The scope is bounded by
`modelrag.retrieval.v2.max-active-build-filter` (default `10000`) and is
resolved with `cap + 1`. An overflow never sends a silently truncated terms
filter: lexical V2 returns an empty degraded channel while semantic V2 may
continue. Returned Elasticsearch hits are batch-validated with
`RetrievalUnitRepository.findActiveByIds`; stale projection hits are dropped
and counted.

---

# 20. Outbox Evolution

Keep the existing outbox pattern.

Do not let Python write Elasticsearch directly.

Extend outbox events from:

```text
UPSERT_CHUNK
```

toward:

```text
UPSERT_RETRIEVAL_UNIT
DELETE_DOCUMENT_VERSION
ACTIVATE_INDEX_BUILD
DELETE_INDEX_BUILD
```

Recommended added columns:

```text
entity_type
entity_id
build_id
partition_key
schema_version
```

Keep existing:

```text
lease
idempotency
dead letter
retry
```

---

# 21. Hybrid Retrieval

Keep the valuable parts of current SearchOrchestrator:

```text
vector
+
BM25
+
parallel execution
+
timeout
+
degraded path
+
RRF
+
conditional rerank
```

Change result representation:

```text
ScoredChunk
```

to:

```text
RetrievalCandidate
```

The hybrid service should stop before document navigation and answer generation.

Suggested target:

```text
HybridRetrievalService
```

Responsibilities:

```text
query rewrite
parallel recall
fusion
rerank
candidate selection
```

Not:

```text
read parent
read neighbor
assemble prompt
generate answer
```

---

# 22. Evidence Expansion

Remove the old full-dataset `expandContext()` pattern.

Add:

```text
EvidenceExpansionService
```

It may call:

```text
findNode
findParent
findChildren
findNeighbors
findReferences
```

All lookups must be bounded.

---

# 23. MMR

MMR may remain.

Do not change every retrieval algorithm during structural migration.

Keep existing MMR behavior initially so evaluation can isolate the effect of document-native refactoring.

---

# 24. QA Refactor

Large QA orchestration should be split.

Target:

```text
QaApplicationService
├── QueryContextResolver
├── EvidenceRetrievalService
├── EvidenceSufficiencyPolicy
├── EvidenceContextAssembler
├── AnswerSynthesizer
└── AnswerAuditService
```

`QaApplicationService` should orchestrate rather than implement every algorithm.

---

# 25. EvidenceContextAssembler

Input:

```text
EvidenceSet
```

not:

```text
List<ScoredChunk>
```

Prompt structure:

```text
[E1]
Document:
TitlePath:
Page:
Content:

[E2]
...
```

The answer model should be instructed to ground claims in evidence identifiers.

---

# 26. AnswerSynthesizer

Add a dedicated final synthesis component.

Old pattern:

```text
Agent
↓
subtask
↓
qa.answer()
↓
QaResult
↓
repeat
↓
combine(results)
```

Target:

```text
Agent
↓
collect evidence
↓
EvidenceSet
↓
AnswerSynthesizer
↓
single final answer
```

This reduces:

```text
repeated token cost
intermediate-summary information loss
cross-subanswer contradiction
citation loss
```

---

# 27. Request Modes

Target modes:

```text
DIRECT_RAG
AGENTIC_RAG
TOOL_AGENT
```

## DIRECT_RAG

Use for simple factual retrieval:

```text
Question
↓
HybridRetrievalService
↓
EvidenceExpansion
↓
EvidenceSelector
↓
EvidenceSufficiency
↓
AnswerSynthesizer
```

## AGENTIC_RAG

Use for multi-hop, cross-document, compare, research, or evidence-completeness tasks:

```text
Question
↓
Agent Runtime
├── search
├── open
├── find
├── navigate
├── follow reference
└── done
↓
EvidenceSet
↓
AnswerSynthesizer
```

## TOOL_AGENT

Use when external/business side effects are involved.

This path requires:

```text
ACL
approval
idempotency
transaction
audit
```

---

# 28. ComplexityRouter / RouteDecision

Evolve current route decision from roughly:

```text
DIRECT_RAG
AGENT
```

to:

```text
DIRECT_RAG
AGENTIC_RAG
TOOL_AGENT
```

Routing guideline:

```text
simple point fact
→ DIRECT_RAG

cross-section / cross-document / comparison / multi-hop
→ AGENTIC_RAG

external side effect
→ TOOL_AGENT
```

---

# 29. Agent Policy

Current Agent planning already has a useful pattern:

```text
LLM -> structured next action
```

Keep that principle.

Evolve:

```text
AgentPlanner
```

toward:

```text
LlmAgentPolicy
```

Interface concept:

```text
AgentState + AvailableActions
    -> AgentDecision
```

The LLM should not receive or expose private chain-of-thought.

It should return structured actions only.

---

# 30. AgentState

Add an explicit state model.

Suggested fields:

```text
executionId
userId
datasetId
conversationId
mode
goal
step
status
observations
evidenceIds
actionHistory
tokenBudget
toolBudget
retrievalBudget
deadline
pendingApproval
lastDecision
stateVersion
```

`stateVersion` should support optimistic locking / lease safety.

---

# 31. AgentDecision

Suggested actions:

```text
DONE
SEARCH
OPEN
FIND
NAVIGATE
CALL_TOOL
REQUEST_APPROVAL
```

Store:

```text
action
arguments
```

Do not persist hidden chain-of-thought.

---

# 32. Agent Runtime

`AgentOrchestrator` should become a facade.

Target public responsibilities:

```text
start()
resume()
cancel()
```

Add:

```text
AgentRuntime
```

Core loop:

```text
load state
↓
guard.check
↓
policy.decide
↓
persist decision
↓
execute action
↓
persist observation
↓
checkpoint
↓
loop
```

---

# 33. Agent Execution Storage

Split current execution registry responsibilities.

Target:

```text
AgentExecutionRepository
```

for database state:

```text
create
find
transition
requestCancel
complete
lease
```

and:

```text
RunningExecutionRegistry
```

for JVM-ephemeral handles:

```text
Thread
Semaphore
interrupt
```

Database remains the source of truth.

---

# 34. Agent Guardrails

Add:

```text
AgentGuardrail
```

Implement:

```text
StepBudgetGuard
DeadlineGuard
LoopGuard
TokenBudgetGuard
ToolAccessGuard
ApprovalGuard
```

Existing loop detection logic can be reused inside `LoopGuard`.

Rule:

```text
LLM decides action.
Java decides constraints.
```

---

# 35. Approval

Keep approval in deterministic Java logic.

The LLM may request:

```text
CALL_TOOL
```

Java reads tool risk metadata and decides whether approval is required.

Do not let the LLM decide authorization or approval policy.

---

# 36. Tool Gateway

Add:

```text
modelrag-tool-gateway
```

Move/evolve tool-related components such as:

```text
HttpToolInvoker
ResilientToolExecutor
ToolCallValidator
ToolCoordinationStore
RedisToolCoordinationStore
ToolDefinition
ToolRegistry
ToolSecretCipher
ToolCallTracer
```

Suggested structure:

```text
modelrag-tool-gateway
├── catalog
│   └── ToolCatalog
├── policy
│   ├── ToolAccessPolicy
│   └── ToolRiskPolicy
├── executor
│   ├── ToolDispatcher
│   └── ResilientToolExecutor
├── http
│   └── HttpToolInvoker
├── security
│   ├── ToolCallValidator
│   └── ToolSecretCipher
└── trace
    └── ToolCallTracer
```

---

# 37. Retrieval Tools

Add retrieval tools for Agentic RAG.

## SearchKnowledgeTool

Input:

```text
query
scope
topK
```

Output:

```text
unitId
document
titlePath
page
score
preview
```

Do not return a full QA answer.

## OpenKnowledgeNodeTool

Input:

```text
nodeId
```

Output:

```text
node content
parent
titlePath
page
references
```

## FindInDocumentTool

Input:

```text
documentId
query
```

Use for scoped document search.

## NavigateKnowledgeTool

Operations:

```text
PARENT
CHILDREN
PREVIOUS
NEXT
REFERENCES
```

Critical rule:

```text
Knowledge Tool -> Evidence / Candidate / Node
```

Never:

```text
Knowledge Tool -> QaResult
```

Final synthesis happens only after evidence collection.

---

# 38. Agent Result

Add metadata:

```text
retrievalTraceId
evidenceCount
stepsUsed
tokensUsed
toolCalls
resumeCount
status
```

Status values:

```text
RUNNING
WAITING_APPROVAL
COMPLETED
FAILED
CANCELLED
TIMEOUT
```

---

# 39. SSE

Keep existing SSE and replay capability.

Add unified safe Agent events:

```text
EXECUTION_STARTED
PLAN
SEARCH_STARTED
SEARCH_COMPLETED
OPEN_STARTED
OPEN_COMPLETED
TOOL_STARTED
TOOL_COMPLETED
APPROVAL_REQUIRED
EVIDENCE_ADDED
SYNTHESIS_STARTED
ANSWER
FAILED
CANCELLED
DONE
```

Do not expose private model reasoning.

SSE replay is for user-visible delivery.

Agent checkpoint is for execution recovery.

They are not the same mechanism.

---

# 40. Idempotency

Keep existing API idempotency.

For side-effect tools, build idempotency keys deterministically:

```text
executionId
+
stepNo
+
toolName
+
canonicalArgumentsHash
```

Do not ask the LLM to generate idempotency keys.

---

# 41. Rate Limits and Budgets

Agent systems need more than HTTP QPS.

Target budget types:

```text
ApiRateLimiter
AgentConcurrencyLimiter
RetrievalRateLimiter
ModelTokenBudget
EmbeddingConcurrencyLimiter
ToolRateLimiter
IndexingConcurrencyLimiter
```

A single HTTP request may produce:

```text
multiple model calls
multiple retrieval actions
multiple reranks
multiple tools
```

So enforce:

```text
steps/request
tokens/request
toolCalls/request
retrievalActions/request
concurrent executions
```

---

# 42. Observability

Add metrics such as:

```text
modelrag.retrieval.duration{stage=semantic|lexical|fusion|rerank|navigation}

modelrag.retrieval.candidates{channel}

modelrag.retrieval.evidence

modelrag.agent.steps

modelrag.agent.resume

modelrag.agent.tool.calls

modelrag.agent.loop.stop

modelrag.ai.rpc.duration{service=embedding|rerank|ocr|document_ai}

modelrag.ai.rpc.errors

modelrag.index.build.duration

modelrag.index.activation

modelrag.index.failure

modelrag.answer.evidence.coverage
```

---

# 43. Failure and Degradation

## Reranker down

Fallback:

```text
Vector + BM25
↓
RRF
↓
use fused result
```

## Embedding query service down

Fallback to:

```text
BM25 only
```

Mark semantic retrieval degraded.

## OCR / Document AI down

Only new document build should fail.

Existing active indexes must continue serving.

## LLM down

Use existing model router fallback if possible.

If all models are unavailable:

```text
fail closed
```

Do not fabricate an answer.

---

# 44. Security / ACL

Keep existing auth/security design.

Extend:

```text
AccessControlService
```

with:

```text
resolveAccessScope()
```

Retrieval must filter authorization before producing candidates.

Do not:

```text
retrieve TopK
then remove unauthorized items
```

Target:

```text
AccessScope
↓
semantic / lexical query
↓
authorized candidates only
```

At large scale, avoid huge lists of allowed document IDs.

Prefer filterable labels such as:

```text
dataset
tenant
role
department
ACL label
```

---

# 45. Frontend Changes

Minimal architecture changes are required.

Update citation types to include:

```text
documentVersionId
nodeId
titlePath
page
evidenceId
```

Chat citation UI should display:

```text
《文档名》
章节路径
页码
```

Knowledge-base UI may expose:

```text
Document Version
Parse Status
Index Build
Active Build
Node Count
Retrieval Unit Count
```

Audit UI should show:

```text
Question
Retrieval Run
├── search
├── open
├── find
└── evidence

Agent Steps

Final Answer
```

Monitor UI may show:

```text
Embedding Service
Reranker
Document AI
Index Build
Agent Runtime
Retrieval latency
```

---

# 46. Data Migration

Preferred migration path:

```text
source object
↓
re-parse
↓
DocumentVersion
↓
DocumentNode
↓
RetrievalUnit
↓
re-index
```

Do not reconstruct new document structure from old chunks when the original source exists.

Fallback only when source is unavailable:

```text
Legacy Chunk
↓
Pseudo Section / DocumentNode
```

Mark metadata:

```text
migrationQuality = LEGACY_RECONSTRUCTED
```

---

# 47. Detailed Class-Level Change Map

| Existing | Action | Target |
|---|---|---|
| `Chunk` | Deprecate later | `DocumentNode + RetrievalUnit` |
| `Document` | Modify | logical document |
| `DocumentParser` | Breaking evolution | `ParsedDocument` |
| `TextDocumentParsers` | Split | local structured parsers |
| `RecursiveCharSplitter` | Keep | retrieval-unit fallback |
| `KnowledgeStore` | Split | bounded repositories |
| `PostgresKnowledgeStore` | Split | JDBC repositories |
| `DocumentService` | Modify | version + ingestion coordinator |
| `DocumentDeletionService` | Modify | version/node/build tombstone |
| `DefaultDocumentVectorizationTool` | Refactor | reindex command |
| `IndexingPipeline` | Split heavily | `IndexBuildCoordinator + stages` |
| `EmbeddingService` | Keep | batch/cache/remote orchestration |
| `PostgresVectorStore` | Refactor | semantic search adapter |
| `PostgresIndexOutbox` | Extend | generic projection outbox |
| `ElasticsearchOutboxConsumer` | Modify | retrieval-unit projection |
| `Bm25Search` | Evolve | lexical search port |
| `ElasticsearchBm25Search` | Modify | unit search |
| `ScoredChunk` | Deprecate | `RetrievalCandidate` |
| `SearchStages` | Modify | `RetrievalStages` |
| `SearchOrchestrator` | Keep core | hybrid retrieval |
| `QueryRewriter` | Mostly keep | query understanding |
| `HttpReranker` | Move/evolve | inference client |
| `Reranker` | Unify | `RerankPort` |
| `RetrievalPipeline` | Major refactor | evidence retrieval |
| `ContextAssembler` | Rename/evolve | `EvidenceContextAssembler` |
| `QaOrchestrator` | Split heavily | `QaApplicationService` |
| `Citation` | Extend | node-level citation |
| `QaResult` | Extend | trace/evidence/coverage |
| `ComplexityRouter` | Modify | 3-route router |
| `RouteDecision` | Modify | DIRECT/AGENTIC/TOOL |
| `AgentPlanner` | Refactor | `LlmAgentPolicy` |
| `AgentOrchestrator` | Split | AgentRuntime facade |
| `AgentExecutionRegistry` | Split | DB repository + JVM registry |
| `LoopDetector` | Move | `LoopGuard` |
| `ApprovalGate` | Keep | deterministic guard |
| `ToolRegistry` | Move | ToolCatalog |
| `HttpToolInvoker` | Move | Tool Gateway |
| `ResilientToolExecutor` | Move | Tool Gateway |
| `ToolCallValidator` | Move | Tool Gateway |
| `ToolSecretCipher` | Move | Tool Gateway |
| `ToolCallTracer` | Move | Tool Gateway trace |
| `ConversationMemory` | Keep | agent context |
| `LongTermMemoryService` | Keep | memory |
| `SseEmitterService` | Keep | event delivery |
| `RedisSseReplayStore` | Keep | replay |
| `AccessControlService` | Extend | AccessScope |
| `DatasetRateLimiter` | Extend | resource budgets |
| `ApiIdempotencyFilter` | Keep | API idempotency |
| `ModelRouter` | Keep in Java | model policy |
| `SpringAiByokChatModels` | Keep in Java | SaaS LLM adapters |

---

# 48. Incremental Execution Roadmap

Do not execute all steps at once.

---

## M0 - Freeze Baseline

Record current quality and performance:

```text
Recall@5
Recall@10
MRR
Citation Hit Rate
Answer Faithfulness
Refusal Accuracy
P50 Retrieval
P95 Retrieval
P95 QA
Tokens / Query
Embedding Cost
Rerank Cost
Heap / Query
DB rows fetched / Query
```

Especially measure current context expansion database row count.

Deliverable:

```text
ModelRAG 1.x baseline
```

---

## M1 - Retrieval P0

Goal:

```text
remove full-dataset chunk loading from query hot path
```

Add bounded methods temporarily to legacy storage:

```text
findChunksByIds
findChunksByParentId
findChunkNeighbors
```

Update retrieval expansion to use bounded SQL.

Acceptance:

```text
1,000,000 chunks
Top5 query
no full-dataset SELECT
context expansion rows < 50
```

---

## M2 - Document-Native Schema

Add V54-V59:

```text
kb_document_version
kb_document_node
kb_node_edge
kb_retrieval_unit
kb_vector_embedding
kb_index_build
```

Add Java domain objects and repositories.

Legacy chunk path still works.

---

## M3 - Structured Ingestion + Dual Write

Refactor parser/indexing:

```text
ParsedDocument
↓
DocumentNode
↓
RetrievalUnit
```

Temporarily dual-write legacy chunks.

Feature flag example:

```text
modelrag.indexing.document-native-enabled
```

---

## M4 - Retrieval V2

Refactor:

```text
PostgresVectorStore
ElasticsearchBm25Search
SearchOrchestrator
RetrievalPipeline
ContextAssembler
```

New path:

```text
RetrievalUnit
↓
Candidate
↓
Evidence
↓
Document Navigation
```

Feature flag:

```text
modelrag.retrieval.v2.enabled
```

Run V1/V2 shadow comparison before cutover.

---

## M5 - Evidence-Centric QA

Refactor:

```text
QaOrchestrator
ContextAssembler
Citation
```

Add:

```text
EvidenceContextAssembler
EvidenceSufficiencyPolicy
AnswerSynthesizer
```

DIRECT_RAG becomes:

```text
retrieve evidence
↓
single synthesis
```

---

## M6 - Agentic Retrieval

Add:

```text
search_knowledge
open_node
find_in_document
navigate
```

Refactor:

```text
AgentPlanner
AgentOrchestrator
ComplexityRouter
```

Prohibit:

```text
knowledge_lookup -> qa.answer()
```

Required trace style:

```text
SEARCH
↓
OPEN
↓
FIND/NAVIGATE
↓
DONE
↓
SYNTHESIS
```

---

## M7 - Durable Agent Runtime

Add V64 checkpoint storage.

Refactor:

```text
AgentExecutionRegistry
→ AgentExecutionRepository
+ RunningExecutionRegistry
```

Add:

```text
AgentRuntime
AgentState
AgentPolicy
LlmAgentPolicy
AgentDecision
AgentCheckpointService
AgentGuardrailChain
```

Critical acceptance:

```text
run to step 3
kill -9 JVM
restart
resume after checkpoint
do not repeat successful side-effect tool
```

---

## M8 - Python AI Plane

Only after the Java architecture stabilizes.

Order:

```text
Embedding
↓
Reranker
↓
Document AI / OCR
↓
Local GPU model serving
```

Do not combine this with the earlier deep Java refactor.

G5 keeps the two retrieval engines separate during migration:

```text
V1: SearchOrchestrator -> ScoredChunk -> current QA
V2: HybridRetrievalService -> RetrievalCandidate -> RetrievalV2Stages
```

V2 reuses `QueryRewriter`, runs semantic and lexical channels in parallel,
deduplicates within each channel by `retrievalUnitId`, and applies weighted
RRF with `retrievalUnitId` as the only fusion key. The existing reranker is
reached only through a private compatibility adapter that maps unit IDs to
temporary `ScoredChunk` IDs and maps the result back to `RetrievalCandidate`.
V2 retrieval is shadow-only in G5. When
`modelrag.retrieval.v2.shadow-enabled` is enabled, a bounded asynchronous
shadow job compares V1 and V2 at document-ID level; queue saturation or V2
failure is recorded and cannot change the V1 response. Evidence construction,
document navigation, and QA cutover are implemented by the subsequent G6 flow.

## G6/G7 implementation status

G6 adds the opt-in `QaV2ApplicationService` evidence flow. `EvidenceSet` is
assembled only from active V2 retrieval units and bounded document navigation;
the final answer path accepts that set, emits citations keyed by
`DocumentVersion` and `DocumentNode`, and refuses without a model call when
the set is insufficient. V1 remains the default read path and
`modelrag.qa.v2.enabled` remains `false` by default.

G7 adds execution-local retrieval actions and the three-mode top-level routing
contract: `DIRECT_RAG`, `AGENTIC_RAG`, and `TOOL_AGENT`. Retrieval actions return
bounded observations/evidence and never call QA answer services. `AGENTIC_RAG`
is read-only, uses observed source identifiers for navigation, and performs at
most one final synthesis from a sufficient `EvidenceSet`; `TOOL_AGENT` retains
the existing authorization, approval, idempotency, and side-effect guards.

G8 adds the durable runtime for the two non-direct modes. It introduces only
`V64__agent_checkpoint.sql`; `V61`-`V63` remain reserved and `V1`-`V60` remain
immutable. `AgentState` is the versioned, bounded, secret-free recovery document
stored in PostgreSQL. `AgentCheckpointService` advances it with an optimistic
checkpoint-sequence CAS while a PostgreSQL lease gives one runtime owner the
right to execute a transition. `AgentExecutionRegistry` remains an ephemeral
execution lookup and does not become the source of truth.

`AgentRuntime` owns `AGENTIC_RAG` and `TOOL_AGENT` transitions, checkpoints a
pending action before execution, preserves the absolute deadline and remaining
budgets, and emits bounded PLAN/ACT/OBSERVE/DONE events. Read-only retrieval
actions may replay after recovery. Idempotent HTTP actions replay with the
persisted idempotency key; an uncertain non-idempotent action stops at
`RECONCILIATION_REQUIRED` and is never auto-replayed. Approval resume reloads
the persisted state before continuing. `DIRECT_RAG` remains unchanged, and
the prior loop adapter is test-profile compatibility only. Tool Gateway
extraction plus G9 are not part of this implementation.

## G11 implementation status

G11 adds bounded retrieval observability, the opt-in retrieval-scale benchmark
harness, and canonical V1/V2 evaluation. `V1` remains the production/default
read path and `modelrag.retrieval.v2.enabled` / `modelrag.qa.v2.enabled` remain
unchanged by this phase. `V1` evaluation preserves chunk labels while resolving
document identities; `V2` evaluation reports document, node, retrieval-unit and
evidence-group identities through separate adapters. Missing or legacy labels
are reported explicitly instead of being converted into synthetic V2 labels.

`V64__agent_checkpoint.sql` is already shipped. The earlier `V61`-`V63`
numbers are reserved historical slots and are intentionally unmaterialized in
this checkout; they must not be back-filled or enabled with Flyway
`outOfOrder`. Forward schema changes therefore continue with
`V65__retrieval_observability.sql` and `V66__evaluation_v2_labels.sql`.

The benchmark's full mode requires a real runtime and a real fixture with at
least one million active retrieval units, 20,000 active documents, 1024-d
vectors, stale builds, and active-build overflow. If that infrastructure is
not available, the harness reports `NOT RUN` and produces no fabricated
measurements.

---

# 49. Recommended Task Numbers

```text
2.0.0  Freeze baseline

2.0.1  Remove full-dataset retrieval
2.0.2  Split KnowledgeStore

2.0.3  DocumentVersion
2.0.4  DocumentNode
2.0.5  Node navigation
2.0.6  RetrievalUnit
2.0.7  IndexBuild

2.0.8  Structured parser
2.0.9  IndexingPipeline decomposition
2.0.10 Dual-write V1/V2

2.0.11 Semantic Retrieval V2
2.0.12 Lexical Retrieval V2
2.0.13 Evidence model
2.0.14 Evidence navigation
2.0.15 QA V2

2.0.16 Retrieval tools
2.0.17 Route DIRECT/AGENTIC/TOOL
2.0.18 LlmAgentPolicy
2.0.19 AgentState
2.0.20 AgentRuntime
2.0.21 Checkpoint/resume

2.0.22 Tool Gateway extraction

2.0.23 Python Embedding service
2.0.24 Python Reranker service
2.0.25 Python Document AI/OCR

2.0.26 Observability upgrade
2.0.27 Million-unit load test
2.0.28 V1/V2 evaluation
2.0.29 V2 default
2.0.30 Legacy cleanup
```

---

# 50. Final Acceptance Criteria

ModelRAG 2.0 must satisfy:

| Area | Acceptance |
|---|---|
| 1M retrieval units | no full-dataset JVM loading |
| Context expansion | DB rows scale with TopK/radius |
| Index build failure | old ACTIVE build keeps serving |
| Elasticsearch down | semantic path can degrade |
| Reranker down | RRF path continues |
| OCR/Document AI down | only new ingestion fails |
| ACL | unauthorized docs never enter candidate set |
| Citation | can locate DocumentVersion + Node |
| Agent restart | JVM restart can resume |
| Tool retry | side effects are not duplicated |
| Agent loop | maxSteps/deadline/budget enforced |
| Final answer | generated from `EvidenceSet` |
| Trace | every retrieval action is visible |
| SSE reconnect | user-visible events can replay |
| Embedding migration | old/new profiles can coexist |
| Reindex | new build switches only after READY |
| Python failure | Java business state stays consistent |

---

# 51. Expected Engineering Improvements

## Scalability

Before:

```text
context expansion ~ dataset size
```

After:

```text
context expansion ~ selected evidence size
```

## Retrieval Quality

Before:

```text
bare chunk
```

After:

```text
title path
+ node semantic type
+ section context
+ document structure
```

## Citation Quality

Before:

```text
chunkId
```

After:

```text
Document
+ Version
+ Section
+ Page
+ Node
+ Excerpt
```

## Agent Quality

Before:

```text
LLM -> QA -> Summary
```

After:

```text
LLM -> Evidence Navigation -> Evidence -> Final Synthesis
```

## Agent Reliability

Before:

```text
local runtime state
```

After:

```text
persistent state
+ checkpoint
+ lease
+ idempotency
```

## Index Reliability

Before:

```text
chunk-centered active version
```

After:

```text
immutable document version
+ explicit index build
+ verification
+ atomic activation
```

## Java/Python Decoupling

Changing the embedding/rerank/OCR implementation should not change Java business logic.

## Independent Scaling

Example:

```text
Java API:       8 instances
Embedding:      4 GPU pods
Reranker:       2 GPU pods
OCR:            1 GPU pod
```

Each plane may scale independently.

---

# 52. Non-Goals During Early Migration

Do not prematurely:

- rewrite the entire repository
- rename all Maven modules
- drop `kb_chunk`
- replace all algorithms at once
- replace MMR while changing the knowledge model
- move SaaS LLM routing to Python
- let Python own business persistence
- introduce a large Knowledge Graph before document structure is stable
- implement all 2.0.x tasks in one Codex session

The migration must remain observable, testable, and reversible.
