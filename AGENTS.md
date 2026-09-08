# ModelRAG Repository Instructions

## 1. Project Context

ModelRAG is a Java 21 modular RAG / Agent system.

Primary backend modules:

- `modelrag-api`
- `modelrag-common`
- `modelrag-knowledge`
- `modelrag-indexing`
- `modelrag-search`
- `modelrag-qa`
- `modelrag-agent`
- `modelrag-server`
- `modelrag-client`

The ModelRAG 2.0 architecture source of truth is:

- `docs/architecture/MODELRAG_2_REFACTOR_PLAN.md`

Before implementing any ModelRAG 2.0 task, read the relevant section of that document.

## 2. Architecture Rules

Java is the control plane, business plane, state plane, and source of truth.

Java owns:

- API
- Auth
- ACL
- transactions
- Agent state
- Tool policy
- approvals
- idempotency
- index lifecycle
- audit
- SSE
- business persistence
- model routing policy
- retrieval orchestration
- observability

Python AI services may provide:

- Embedding
- Reranker
- OCR
- Document AI
- local model inference
- GPU inference

Python services MUST NOT:

- own business state
- directly mutate ModelRAG business tables
- decide authorization
- own Agent execution state
- own transaction boundaries
- own approval state
- bypass Java audit / idempotency rules

## 3. Database Rules

Flyway migrations `V1-V53` are immutable.

Never modify `V1-V53`.

All ModelRAG 2.0 schema changes must start from `V54` and use additive migrations.

Do not drop legacy schema until the explicit legacy-cleanup phase.

During migration, V1 and V2 data paths may coexist.

## 4. Refactoring Strategy

ModelRAG 2.0 is an incremental migration.

Do NOT perform a big-bang rewrite.

Each `2.0.x` task must remain independently:

- buildable
- testable
- reviewable
- reversible

Do not implement later phases unless explicitly requested.

Do not perform unrelated cleanup during a scoped task.

Feature flags and dual-write are allowed when needed for safe migration.

## 5. Retrieval Rule

No online retrieval hot path may load all chunks, nodes, retrieval units, or documents of a dataset into JVM memory.

Query cost must depend on:

- TopK candidate count
- evidence count
- navigation radius
- bounded query scope

and must not scale linearly with the total dataset size.

## 6. Knowledge Model Rule

The long-term knowledge model is:

- `Document` = logical document
- `DocumentVersion` = immutable content snapshot
- `DocumentNode` = structural knowledge object
- `RetrievalUnit` = retrieval projection
- `Evidence` = runtime evidence object

`Chunk` is legacy compatibility data and must not remain the long-term domain center.

## 7. Agent Rule

LLM is allowed to choose actions.

LLM is NOT allowed to own constraints.

Deterministic Java code must control:

- authorization
- max steps
- deadline
- token budget
- retrieval budget
- tool budget
- approval
- idempotency
- transaction boundaries
- persistence
- cancellation
- recovery

Knowledge tools must return candidates / nodes / evidence, not complete QA answers.

Final answer synthesis should happen once, from the final `EvidenceSet`.

## 8. Tool Rule

Business-side-effect tools must pass through deterministic Java controls:

- ACL
- validation
- risk policy
- approval
- idempotency
- timeout
- retry
- tracing

The LLM may request a tool action, but Java decides whether it is allowed.

## 9. Indexing Rule

Indexing must use an explicit lifecycle.

Remote operations such as parsing, OCR, embedding, reranking, and Elasticsearch synchronization must not be kept inside long database transactions.

An index build may only become active after verification succeeds.

If a new build fails, the previous active build must continue serving traffic.

## 10. Coding Rules

- Use Java 17.
- Keep module boundaries explicit.
- Prefer ports/interfaces between modules.
- Do not introduce unnecessary dependencies.
- Avoid God Services / God Repositories.
- Keep SQL bounded and index-backed.
- Prefer additive schema evolution.
- Preserve existing public API behavior unless the task explicitly changes it.
- Do not rename Maven modules during unrelated refactors.

## 11. Validation Rules

After modifying code:

1. compile affected modules;
2. run affected unit tests;
3. run relevant integration tests where available;
4. inspect Flyway compatibility when schema changes;
5. verify no full-dataset query was introduced in a hot path;
6. report all changed files;
7. report tests executed and results;
8. report remaining risks.

## 12. Execution Rule

For every `2.0.x` task:

1. read this file;
2. read `docs/architecture/MODELRAG_2_REFACTOR_PLAN.md`;
3. read the task file under `docs/exec-plans/`;
4. inspect actual repository code before changing anything;
5. provide a concise implementation plan;
6. implement only the requested task;
7. run validation;
8. stop after the requested task.

Do not automatically continue to the next task.
