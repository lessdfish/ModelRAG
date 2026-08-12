# ModelRAG Spring AI 中等范围重构计划

> 日期：2026-08-12  
> 状态：待实施，作为本轮重构的执行基线  
> 适用范围：单机 Docker Compose 部署、中心化多用户知识库问答  
> 关系：本文件以当前代码审计为依据；与 `2026-07-06-modelrag-design.md` 冲突时，以本文件为准

## 1. 目标与边界

本轮将现有项目重构为基于 Spring AI 的知识库问答助手，并为未来封装 Java SDK / Spring Boot Starter 保留稳定边界。采用模块化单体，不拆微服务。

必须完成：

- Java 21、Spring Boot 4.1、Spring AI 2.0.0 stable。
- PostgreSQL + pgvector 是默认且唯一的业务事实库；默认启动必须连接 PostgreSQL。
- 会话、消息、摘要、长期记忆、文档元数据、索引状态和审计数据均从数据库读写，不允许生产代码回退到 JVM `Map` 或进程内存。
- 原文件写入 S3 兼容对象存储，默认 MinIO；PostgreSQL 不保存整份原始二进制。
- 支持用户自带模型密钥，覆盖主流模型和 OpenAI-compatible 服务。
- Qwen3-Embedding-0.6B（Ollama，1024 维）替换并彻底删除 BGE-large-zh-v1.5；全量重建向量，不兼容旧向量。
- 向量余弦检索与 Elasticsearch BM25 并行召回，RRF 融合，按条件启用 reranker，再执行 MMR 和证据装配。
- 支持意图识别、问题改写、引用、ReAct、自定义工具、最大步长、审批、超时和循环检测。
- 删除在线 A/B 实验与最终答案缓存，保留离线评测和固定初始权重。
- 将真实企业文档规模、异常文件、大文件内存边界和入库恢复能力纳入验收。
- 保持中心化多用户，所有用户数据通过 `userId + datasetId` 隔离；本轮不引入租户模型。

本轮不做：

- 微服务、Kubernetes、多地域容灾和 Redis 集群。
- MCP 工具协议；首版只支持 Java Bean Tool 与 HTTP + JSON Schema Tool。
- OCR 和图片理解；扫描 PDF 必须明确返回 `OCR_REQUIRED`，不能静默生成空知识库。
- 在线自动调权和在线 A/B；校准仅通过离线评测集、回放和人工标注完成。
- 发布完整 SDK；本轮只建立可抽取的 API、DTO、SPI 和门面，SDK 在下一阶段打包。
- 多租户组织、租户角色、租户配额和租户管理界面。

## 2. 当前项目结论

当前代码不是“PostgreSQL 默认运行”的系统，而是“默认内存实现、`postgres` Profile 才启用数据库”的双模式系统：

- 默认配置排除了 DataSource、Flyway 和 Redis 自动配置。
- `KnowledgeStore`、向量存储、BM25 和索引 outbox 存在 `!postgres` 内存实现。
- 多个服务通过 `ObjectProvider<JdbcTemplate>` 判断数据库是否存在，异常后继续使用 JVM `Map`。
- Docker Compose 显式设置 `postgres` Profile，但 IDE、测试或直接启动会进入另一套行为。
- `postgres` Profile 下，会话、消息、摘要和长期记忆实际存于 PostgreSQL/pgvector；浏览器本地只保存登录 Token，当前页面消息只是 React 临时状态并会从后端重新加载。
- 非 PostgreSQL 模式下，会话和长期记忆落入后端 JVM 内存，知识库正文另存为本地 `knowledge-store.json`，重启后两套数据的存续行为不同。

这会产生两套语义、重启丢数据、节点间不一致和数据库短暂故障后的“假成功”。重构后只保留一个生产运行模型：PostgreSQL 为强依赖；测试替身仅能在 `test` Profile 中装配。

当前样例文档也不足以代表真实用户负载：样例正文约一千余字符，测试主要使用单句或重复文本。与此同时，上传使用 `MultipartFile.getBytes()`，解析、分块和向量列表大多整批驻留内存，较大文件容易引发内存峰值、长事务和无进度等待。

## 3. 目标架构

```mermaid
flowchart LR
    UI[React Client] --> API[/api/v2]
    SDK[Future Java SDK] --> FACADE[KnowledgeAssistant API]
    API --> FACADE
    FACADE --> INTENT[Intent + Rewrite]
    INTENT --> RAG[Direct RAG Pipeline]
    INTENT --> AGENT[Bounded ReAct Agent]
    RAG --> RET[Hybrid Retrieval]
    AGENT --> RET
    AGENT --> TOOLS[Java / HTTP Tools]
    RET --> PG[(PostgreSQL + pgvector)]
    RET --> ES[(Elasticsearch BM25)]
    FACADE --> MEMORY[Conversation + Memory Service]
    MEMORY --> PG
    INGEST[Document Ingestion] --> OBJ[(MinIO)]
    INGEST --> PG
    PG --> OUTBOX[Versioned Outbox]
    OUTBOX --> ES
    RAG --> MODEL[Spring AI Model Gateway]
    AGENT --> MODEL
    MODEL --> CLOUD[BYOK Cloud Models]
    MODEL --> OLLAMA[Local Ollama]
    CACHE[Caffeine] -. derived cache only .-> MODEL
    CACHE -. query embedding only .-> RET
    REDIS[(Redis)] -. rate limit / idempotency / SSE .-> API
```

### 3.1 模块边界

继续使用模块化单体，减少已有模块的大规模迁移，只新增一个轻量公共契约模块：

- `modelrag-api`：`KnowledgeAssistant`、请求/响应 DTO、错误模型、Tool/Model/Secret SPI；不得依赖 Servlet、JDBC、Redis、Elasticsearch。
- `modelrag-server`：Controller、Spring Security、配置、装配和运行入口。
- `modelrag-knowledge`：知识库、文档版本、对象存储、解析和权限。
- `modelrag-indexing`：入库状态机、Embedding 批处理、pgvector 写入、outbox。
- `modelrag-search`：双路召回、RRF、条件重排、MMR、证据选择。
- `modelrag-qa`：意图、改写、上下文装配、回答生成与引用。
- `modelrag-agent`：有界 ReAct、工具、审批和步骤追踪。
- `modelrag-common`：仅保留内部通用基础设施；移走对外 DTO，并逐步消除 Web/JDBC/Redis 混杂。
- `model-runtime`：缩减为可选 reranker 服务；不再承载旧 Embedding 和重复意图分类链路。

将约 981 行、同时承担检索、回答、缓存、审计、A/B 和 SQL 的 `QaOrchestrator` 拆为四个明确职责：`AnswerApplicationService`、`RetrievalPipeline`、`ContextAssembler`、`AnswerTraceRepository`。不再继续拆成更多小服务。

## 4. 强制持久化与高可用语义

### 4.1 PostgreSQL 默认启用

- 从默认 `application.yml` 删除 DataSource/Flyway 的排除项，默认读取 `SPRING_DATASOURCE_*`。
- PostgreSQL 连接、Flyway 迁移和 pgvector 扩展检查失败时启动失败；不得自动切到内存模式。
- 运行中 PostgreSQL 不可用时，依赖数据库的 API 返回统一 `503 DEPENDENCY_UNAVAILABLE`，并标记 readiness 为失败；不得返回只存在 JVM 中的结果。
- 删除所有 `@Profile("!postgres")` 生产 Bean、`ObjectProvider<JdbcTemplate>` 可选数据库分支、数据库异常后的 Map fallback。
- 所有数据库异常必须进入结构化日志和指标，禁止空 `catch`、仅 debug 记录后继续以及“写失败但接口成功”。
- 内存 Repository/Fake 迁到测试源码，只允许 `test` Profile 使用。测试启动若遗漏 Profile，应直接失败。
- Flyway 只追加新版本，不修改已经发布的 V1～V35 脚本。

### 4.2 哪些内存允许存在

进程内存只允许保存“可丢弃且能从事实源重建”的缓存：

- Caffeine：只缓存查询 Embedding 和模型客户端配置，统一采用 64 MiB 权重上限并设置 TTL、命中率/驱逐指标；密钥不作为明文 key/value 暴露。
- 禁止缓存最终答案、会话消息、长期记忆、文档状态、审批状态和工具调用事实。
- Redis 不是事实库，只用于限流、幂等键、短期执行租约、SSE 临时游标和短时重放；Redis 故障时普通直接 RAG 可在不依赖 Redis 的路径上运行，需要幂等保证或有外部副作用的 Agent/工具请求必须拒绝执行，且不能伪造业务状态。

### 4.3 单机部署可靠性

- PostgreSQL、Elasticsearch、Redis、MinIO 全部配置命名卷；当前 Compose 无持久卷的问题必须修复。
- Redis 单节点 + AOF `everysec`，明确“不宣称 Redis HA”。未来多机再选择 Sentinel 或托管 Redis。
- PostgreSQL 每日逻辑备份，至少每月做一次恢复演练；MinIO 开启版本化并备份。Elasticsearch 和向量可由 PostgreSQL + MinIO 重建，不作为唯一副本。
- 容器设置 healthcheck、restart policy、资源上限和非 root 用户；开发端口默认绑定 `127.0.0.1`。
- 禁止仓库内默认密码。首次启动从 `.env`/外部 Secret 注入，不完整配置直接失败。

## 5. 会话、短期记忆与长期记忆

### 5.1 数据与读取规则

- `kb_conversation`：会话元数据、所属用户、数据集、标题、状态和最后活动时间。
- `kb_message`：用户/助手/工具消息、模型、token、引用和 trace id；消息先落库再进入后续异步处理。
- `kb_context_summary`：摘要版本、覆盖消息范围、生成模型、prompt 版本和生成时间。
- `kb_user_memory`：至少包含 `id、userId、datasetId、scope、type、memoryKey、content、status、sourceConversationId、sourceMessageId、importance、confidence、embedding、embeddingProfileVersion、expiresAt、confirmedAt、createdAt、updatedAt、deletedAt`；不增加任何租户字段。
- 每次问答都从 PostgreSQL 读取“有效摘要 + 最近 8 条原始消息”；不从后端 JVM 会话读取。
- Spring AI `ChatMemory` 通过数据库适配器使用上述会话表，不启用进程内 ChatMemory Repository。
- 普通 RAG、自动知识库选择、问题改写与 Agent 共用同一 `ConversationContextBuilder`。修复当前普通问答只写不读历史，以及 Agent 子请求清空 `conversationId` 导致上下文无法注入的问题。
- 模型上下文固定为“有效滚动摘要 + 最近 8 条原始消息 + 当前问题”；独立问题按规则直达，依赖上下文的问题用一次结构化模型调用联合生成独立问题、意图、知识库候选、缺失参数、置信度和 rerank 判定。

### 5.2 摘要策略

- 每新增 12 条消息异步生成一次模型摘要，保留最近 8 条原始消息。
- 摘要任务使用用户当前选择的模型，单独计量 token 和费用；失败不覆盖上一版本，可重试。
- 摘要必须结构化保留：用户目标、已确认事实、未解决问题、关键约束、引用文档和工具结果；禁止继续使用字符串截断代替摘要。
- 摘要保存覆盖的起止消息 ID、模型、prompt 版本和 token 用量；以 `conversationId + coveredEndMessageId` 唯一键保证任务幂等，避免重复摘要和重复计费。
- 摘要生成时使用乐观锁/覆盖范围校验，避免并发对话覆盖更新；失败保留任务状态并补偿重试，不阻塞当前回答，也不覆盖上一有效摘要。
- 摘要费用在用量页面单列为“会话摘要”，不计入知识库回答缓存或普通回答指标。

### 5.3 长期记忆规则

- 用户明确说“记住……”时可直接保存，并在响应中提供撤销入口。
- 模型推断出的偏好/事实只能创建建议，用户确认后转为有效记忆；禁止从模型回答中用正则自动提取并直接持久化，以免保存幻觉。
- 用户偏好作用域为用户全局；业务事实作用域为“用户 + 数据集”，禁止跨知识库注入。
- 删除普通用户共享的 `global` 记忆；平台公共规则只能配置在独立 System Prompt 中。
- 用户偏好最多注入 3 条；业务事实按“用户 + 数据集”向量检索 Top3，初始余弦阈值不低于 0.60，并同时执行过期过滤和总 token 预算。
- 记忆作为“不可信辅助数据”注入独立 Prompt 区域，不得覆盖系统安全规则，也不能替代知识库证据。
- 明确偏好保留到用户删除；已确认业务事实默认 180 天；`PENDING_CONFIRMATION` 建议 7 天后过期；归档会话默认 90 天后清理。
- 支持查看、确认、拒绝、修改、删除、全部清除、暂停、恢复、导出和撤销。用户首次启用长期记忆时展示用途与保留策略，未同意时只使用会话短期记忆。
- 使用数据库唯一约束处理同一用户、作用域和 `memoryKey` 的重复及冲突更新；禁止通过模糊 `LIKE` 删除记忆。
- 长期记忆正文、来源和修改均进入审计，但日志不得输出完整敏感内容。

## 6. 真实文档入库设计

### 6.1 文件工具封装

建立单一入口 `DocumentVectorizationTool`，内部用 `DocumentParserRegistry` 按 MIME/魔数选择解析器：

```text
DocumentVectorizationTool
  -> DocumentSafetyScanner
  -> ObjectStorageService
  -> DocumentParserRegistry
       -> PdfDocumentParser
       -> DocxDocumentParser
       -> MarkdownDocumentParser
       -> TextDocumentParser
  -> StructureAwareChunker
  -> EmbeddingBatcher
  -> VersionedIndexWriter
```

新增文件类型只需实现 `DocumentParser` 并注册，不修改主流程。首版仅支持 PDF、DOCX、Markdown、TXT。

### 6.2 解析质量

- PDF 保留页码、标题候选、段落顺序和表格边界，清理重复页眉页脚。
- DOCX 读取段落、层级标题、表格、页眉页脚和列表；不能只读 paragraph。
- Markdown 保留标题路径、列表、表格和代码块；代码块不可被普通段落规则拆碎。
- TXT 做编码探测、换行归一化和超长行保护。
- Chunk 改为“结构优先 + token 预算”：默认子块约 600 tokens、重叠 80 tokens，父块约 1800 tokens；表格、标题与正文保留父子关系。参数可按数据集调整，但重叠不得超过 25%。

### 6.3 大文件与安全边界

默认可配置限制：单文件 50 MiB、最多 1000 页、最多 500 万抽取字符、解析超时 120 秒。超过限制返回明确错误，不把文件读入堆后再判断。

- 上传流直接写 MinIO 并同步计算 SHA-256，禁止 `MultipartFile.getBytes()`。
- 按窗口解析/分块，默认每批最多 100 chunks；Embedding 默认批量 32 条、最大并发 2，并按 provider token 限制动态缩小。
- 校验文件魔数与声明 MIME；限制 ZIP 解压比、条目数、嵌套层级和总展开大小，防止 zip bomb。
- 精确 hash 去重；对重复页眉页脚和规范化正文另做内容 hash，避免重复向量。
- 提供 `DocumentSafetyScanner` SPI：首版实现类型/压缩包/大小检查，可选接入 ClamAV，不把病毒扫描写死在主流程。
- 支持取消、失败重试、可观察进度和断点恢复；解析/Embedding 失败不得把文档标记为 READY。

### 6.4 存储与一致性

- MinIO 保存原文件和解析后的规范化 artifact；PostgreSQL 保存文档/版本元数据和 chunk 正文，不再在 `kb_document` 保存整份 `source_content`。
- 引入文档索引版本：`BUILDING -> VECTOR_READY -> SEARCH_SYNCING -> READY/FAILED`。
- 新版本未 READY 前继续查询旧 active version；只有 pgvector 和 Elasticsearch 均完成后原子切换 active version。
- chunk、embedding 元数据和 outbox 事件在同一数据库事务提交。outbox worker 使用批量 claim、租约超时、`SKIP LOCKED`、幂等键、最大重试和死信状态，避免永久卡在 PROCESSING。
- 删除文档采用软删除 + 延迟清理；清理任务按 chunk/vector、ES 文档、解析 artifact、原文件的顺序执行并可重试。

## 7. 企业文档验收数据集

现有四份短样例保留为 smoke test，但不再作为主要验收依据。新增不含真实隐私数据、可再生成的合成/授权语料：

| 类型 | 规模 | 必须包含 | 主要验证 |
|---|---:|---|---|
| PDF 制度 | 10～20 页 | 页码、目录、重复页脚、跨页表格、制度编号 | 页级引用、去页脚、表格召回 |
| DOCX 手册 | 30～50 页 | 多级标题、目录、列表、表格、页眉页脚 | 结构分块、标题路径、更新重建 |
| Markdown 技术文档 | 2 万字符以上 | 代码块、表格、链接、嵌套列表 | 代码/表格不破坏、关键词召回 |
| TXT 操作手册/日志 | 10 万字符以上 | 超长行、编号、混合中英文 | 编码、流式处理、精确词召回 |
| 大文档 | 100～300 页或 10～50 MiB | 重复章节、稀疏答案、异常页 | 内存上界、进度、取消、恢复 |
| 异常文件 | 边界组合 | 空文档、损坏文件、伪 MIME、zip bomb、扫描 PDF | 明确失败、安全限制、无脏索引 |

大型 fixture 通过测试脚本生成，不把几十 MiB 二进制直接提交仓库。测试问题必须覆盖：文档内答案、跨段答案、编号精确匹配、表格答案、无答案、冲突答案、过时版本、权限隔离和提示注入文本。

## 8. 模型、密钥和 Spring AI 接入

### 8.1 模型提供方

- 原生适配：OpenAI、Anthropic、Google Gemini、DeepSeek、Ollama。
- OpenAI-compatible：Qwen、智谱、Kimi、豆包、MiniMax、Groq、vLLM 等，通过 base URL + model + capability 配置接入。
- 每个模型配置能力矩阵：stream、tool calling、JSON schema、vision、context window、max output、embedding/rerank。保存配置前执行探测，不按供应商名称猜能力。
- 平台不提供聊天模型兜底密钥；调用时必须使用用户自己的有效配置。本地 Ollama 是用户显式选择，不是静默 fallback。

### 8.2 密钥安全

- 密钥由可替换 `SecretProtector` 保护，默认 AES-256-GCM 信封加密；根密钥必须来自外部环境/Secret，不入库、不入日志。
- 预留 Vault/KMS 实现，不要求本轮部署。
- API 只返回掩码和元数据，不返回明文；支持轮换、吊销、连通性测试和按用户隔离。
- 云模型调用前提示数据出站范围：问题、检索证据、会话摘要可能发送给所选 provider。

### 8.3 Embedding 与 reranker

- 唯一官方 Embedding 为 Qwen3-Embedding-0.6B，Ollama 服务，1024 维；删除 BGE-large-zh-v1.5 配置、代码、镜像、模型文件和文档。
- 升级期间新旧索引不能混查；执行全量 reindex，校验维度、文档数、chunk 数和抽样召回后切换。
- Embedding 服务失败必须失败/重试，禁止生成确定性假向量污染索引。
- reranker 不是强制依赖。保留 `bge-reranker-v2-m3` 为可选模型；上下文追问/含糊问题、向量与 BM25 Top5 重合度低于 40%、融合 Top1/Top2 分差低于 10%，或跨语言/候选量较大时启用。其他简单查询跳过；500ms 超时后保留 RRF + MMR 结果并返回降级标记。

## 9. 检索、校准与响应时间

### 9.1 固定主流程

1. 从 PostgreSQL 读取摘要与最近 8 条消息；独立问题规则直达，依赖上下文时以一次结构化调用联合完成问题改写、意图识别、候选知识库、缺失参数、置信度和 rerank 判定。
2. 自动知识库选择先按知识库名称、描述和路由向量预选 Top3，再对最佳候选执行一次正式检索；禁止对每个可访问知识库逐库执行完整混合检索。
3. pgvector 余弦和 Elasticsearch BM25 并行召回。
4. RRF 融合，初始权重向量 0.7、BM25 0.3。
5. 按明确条件 rerank；500ms 超时跳过。
6. MMR 去冗余，父块补全，上下文按 token 预算装配。
7. 生成带文档、页码/章节、chunk 版本的引用；证据不足时明确拒答或追问。

补充实现约束：

- Elasticsearch 使用版本化 index + alias。中文正文/标题采用官方 SmartCN analyzer，编号、文档代码、人员/产品名增加 `keyword` 精确字段；所有节点必须安装同版本插件。
- pgvector 固定到 0.8+，过滤近似检索启用 iterative scan，并用 exact search 对照测 recall；HNSW 调优必须基于离线集，不凭经验改权重。
- 两路召回使用独立受限 executor、共享 deadline 和单路超时。任一路失败时可用另一结果降级，但响应必须携带 `degradedComponents`，不能吞异常伪装成“没有资料”。
- ACL、用户/数据集作用域、文档 active version 和软删除条件必须同时应用到两个检索通道。
- Trace 同时记录原始问题、独立问题、候选知识库、选择原因、是否 rerank 和各阶段版本，便于回放自动路由错误。

### 9.2 意图与改写决策

- 置信度至少 0.80 且第一、第二意图差值至少 0.15：直接执行。
- 0.55～0.80：只允许读操作验证；涉及写入、外部工具或缺少槽位时先澄清。
- 低于 0.55、存在高风险或必要槽位缺失：向用户澄清。
- 改写结果保存原问题、检索问题和引用的历史消息 id，便于回放；不得让改写改变用户原始意图。
- 结构化路由结果统一包含 `intent、standaloneQuestion、datasetCandidates、missingSlots、confidence、requiresRerank、riskLevel`；不得在改写、意图和知识库选择间重复调用模型。

### 9.3 延迟预算

| 阶段 | 目标 |
|---|---:|
| 状态/会话读取 | p95 < 300 ms |
| 意图 + 改写 | p95 < 800 ms（优先一次结构化调用或规则直达） |
| 双路检索 + 融合 | p95 < 800 ms |
| 可选 reranker | 独立预算 500 ms，超时跳过 |
| 首 token | p95 <= 3 s |
| 普通直接 RAG 完整回答 | p95 <= 10 s |

体验规则：简单问答不进入 Agent；意图明确时不调用改写模型；真正 token streaming，不以一次性整段 SSE 伪装流式；300 ms 内返回 accepted/status 事件；长任务持续推送阶段、耗时和可取消状态。

### 9.4 权重与校准验证

- 删除在线 A/B 表、接口、分流和 UI；初始权重保持 0.7/0.3。
- 建立版本化离线评测集和人工 relevance judgement，指标至少包含 Recall@K、MRR、nDCG@K、引用正确率、忠实度、拒答准确率、p95 延迟和 token 成本。
- 校准采用离线 grid search / Bayesian search，但候选参数必须在固定验证集上比较，并用独立测试集验收，避免过拟合。
- 每次模型、chunk、analyzer、权重或 prompt 变更都保存版本并跑回归；上线门槛是质量不下降、延迟/成本在预算内。暂不自动把评测结果写回生产配置。

## 10. ReAct 与自定义工具

- 默认最大步长 6，用户/管理员可在 1～10 范围内下调或上调；到达上限返回已完成步骤、未完成原因和可继续建议。
- 循环检测：相同 tool + 规范化参数重复 2 次，或连续 3 步无新观察，立即停止。
- 每步有总 deadline、单工具 timeout、token budget 和 cancellation；不得仅靠 while 次数保护。
- 工具按 `READ_ONLY`、`WRITE`、`EXTERNAL_SIDE_EFFECT` 分级。后两类默认需要显式审批，并有幂等键与审计。
- HTTP Tool 防 SSRF：仅允许 HTTPS、域名 allowlist、DNS/IP 二次校验、禁止内网/metadata 地址、限制重定向、响应大小和超时。
- Tool 参数使用 JSON Schema 和 Jakarta Validation；写工具只对明确可重试错误重试，且必须声明幂等性。
- 仅 `READ_ONLY` 或显式声明 `IDEMPOTENT` 的工具允许自动重试；副作用工具必须提供幂等键并默认不重试。
- 不向前端暴露原始 chain-of-thought。SSE/API 只返回 `PLAN/ACT/OBSERVE/ANSWER` 安全摘要、工具名、参数摘要、结果状态和引用。
- Agent 执行范围、步骤摘要、终态和审批记录写入 PostgreSQL；Redis 只保存短期执行租约、幂等键和 SSE 游标。
- 检索文档属于不可信数据；文档中“忽略规则/调用工具”等内容永远不能变成系统指令或工具授权。

## 11. API、SDK 准备与安全

- 新接口统一 `/api/v2`，允许 breaking change，前端同步迁移；旧接口仅在明确迁移窗口内保留适配层。首版端点固定为：

| 能力 | 端点 |
|---|---|
| 问答/执行 | `POST /api/v2/assistant/ask`、`POST /api/v2/assistant/streams`、`DELETE /api/v2/assistant/executions/{executionId}` |
| 会话 | `GET/POST /api/v2/conversations`、`GET/DELETE /api/v2/conversations/{conversationId}`、`POST /api/v2/conversations/{conversationId}/archive`、`GET /api/v2/conversations/{conversationId}/export` |
| 长期记忆 | `GET /api/v2/memories`、`POST /api/v2/memories/remember`、`POST /api/v2/memories/{memoryId}/confirm`、`POST /api/v2/memories/{memoryId}/reject`、`PUT/DELETE /api/v2/memories/{memoryId}`、`DELETE /api/v2/memories` |
| 记忆设置 | `GET/PUT /api/v2/memory-settings`，支持暂停、恢复和保留策略 |
| 文档 | `POST /api/v2/datasets/{datasetId}/documents`、`POST /api/v2/documents/{documentId}/reindex`、`GET /api/v2/documents/{documentId}/index-status`、`DELETE /api/v2/documents/{documentId}` |
| 用户模型 | `GET/POST /api/v2/model-configs`、`POST /api/v2/model-configs/{configId}/validate`、`DELETE /api/v2/model-configs/{configId}` |
| 工具/审批 | `GET/POST /api/v2/tools`、`PUT/DELETE /api/v2/tools/{toolId}`、`GET /api/v2/approvals`、`POST /api/v2/approvals/{approvalId}/approve`、`POST /api/v2/approvals/{approvalId}/reject` |
| 离线评测/回放 | `POST /api/v2/evaluations`、`GET /api/v2/evaluations/{taskId}`、`GET /api/v2/traces/{traceId}/replay` |

- Controller 不再收发裸 `Map<String,Object>`；使用不可变 DTO、Jakarta Validation、统一错误码、分页和 OpenAPI。
- `modelrag-api` 固定公开 `KnowledgeAssistant`、`ConversationContextBuilder`、`ConversationRepository`、`LongTermMemoryStore`、`MemorySuggestionService`、`DocumentVectorizationTool`、`ChatModelProviderFactory`、`Retriever`、`Reranker`、`RetrievalPolicy`、`ToolDefinition` 和 `ToolInvoker`；这些契约不得依赖 Controller、Servlet、JDBC、Redis、Elasticsearch 或 React。
- 对外核心门面只依赖 `modelrag-api`；Spring Boot 自动配置、HTTP 客户端和认证随后可独立包装为 Starter/SDK。
- 加入 Spring Security。替换当前 SHA-256 密码方案为 Argon2id，使用短期 Access Token + 可撤销 Refresh Token；禁止通过可伪造 header 识别用户，并移除数据库迁移中的默认管理员账号。
- 生产缺少 Token 签名密钥或模型密钥根密钥时拒绝启动；开发环境也必须显式提供，不内置可用默认值。
- SSE/WebSocket 不在 URL query 放长期 token；使用短期一次性 ticket 或受保护 cookie/header。
- 上传、问答、模型调用、工具调用分别限流；所有写入支持 idempotency key。
- 日志和 trace 对密钥、完整 Prompt、长期记忆、文档正文和个人数据做脱敏与采样。

## 12. 独立审计发现的其他缺陷及处理

| 优先级 | 缺陷 | 计划处理 |
|---|---|---|
| P0 | 默认内存/数据库双实现，异常时静默回退 | 只保留 PostgreSQL 生产实现；测试 fake 隔离 |
| P0 | 会话写入但普通 RAG 不读取，Agent 丢 `conversationId` | 统一 `ConversationContextBuilder` 并做端到端测试 |
| P0 | 自动知识库选择只看当前问题，代词追问会选错库 | 统一 `ConversationContextBuilder`，先生成独立问题再路由 |
| P0 | 最终答案缓存缺少用户、会话、模型、Prompt 和权限维度，可能跨用户复用 | 完全删除最终答案缓存 |
| P0 | Embedding 失败生成假向量 | 删除假向量，失败进入可恢复状态 |
| P0 | 密码 SHA-256、header 身份、URL token、默认密码 | Spring Security、Argon2id、短期 ticket、外部 Secret |
| P0 | HTTP Tool 可访问任意 URL，副作用工具也会自动重试 | SSRF 防护、风险分级、幂等键和受限重试 |
| P0 | Compose 数据服务无持久卷 | 为 PG/ES/Redis/MinIO 增加卷、备份与恢复检查 |
| P1 | 文档全量进堆、整批向量、无 zip bomb/页数限制 | 流式对象存储、窗口处理、资源/安全限制 |
| P1 | 文档 chunk 逐条串行调用 Embedding，入库吞吐低 | 按 provider token 限制执行批量请求和受限并发 |
| P1 | 索引 READY 与 ES outbox 不一致，部分结果可见 | 版本化索引状态、事务 outbox、完成后切 active |
| P1 | ES 中文使用 standard analyzer，mapping 运行时临时创建 | SmartCN + keyword 字段，版本化模板和 alias |
| P1 | pgvector ANN 过滤可能漏召回 | pgvector 0.8+ iterative scan，exact-vs-ANN 校准 |
| P1 | QA、入库和 SSE 共用 executor，CallerRuns 反压请求线程 | 入库/检索/模型分隔 bulkhead，显式队列和拒绝策略 |
| P1 | 两路检索无统一 deadline，异常被吞为空结果 | 并行 deadline、结构化降级结果和指标 |
| P1 | 自动知识库匹配逐库执行完整混合检索，延迟随知识库数线性增长 | 名称/描述/路由向量预选 Top3，再正式检索最佳候选 |
| P1 | `AgentOrchestrator` 计算路由后又强制进入 Agent | 保留统一路由结果，简单问答直接 RAG |
| P1 | ReAct 硬编码 20 步并通过 SSE 暴露 Thought | 默认 6、范围 1～10；只输出安全步骤摘要 |
| P1 | 工具幂等、熔断、权限和 Agent Action 使用无界 JVM Map | 事实写 PostgreSQL，短时执行状态进有界 Redis 租约 |
| P1 | 工作区出现 Native OOM，按数量缓存和无界 Map 放大风险 | 移除无界状态，Caffeine 64 MiB 权重上限并监控 native/heap |
| P1 | PostgreSQL 连接中断可见性和恢复状态不足 | 统一 503/readiness、连接池指标、结构化异常和恢复告警 |
| P1 | `QaOrchestrator` 过大，SQL/缓存/A-B/生成混杂 | 拆为四个职责服务，删除在线 A/B 和答案缓存 |
| P1 | 当前摘要只是截断拼接，近期消息未完整注入且短会话没有上下文 | 模型滚动摘要 + 最近 8 条原始消息，任务幂等与失败补偿 |
| P1 | 长期记忆从模型答案自动抽取，可能固化幻觉 | 显式记忆或用户确认；来源、范围、期限、CRUD |
| P1 | 长期记忆缺少列表、确认、修改、暂停、删除、导出和撤销 | 补齐治理 API、状态机、保留期限和审计 |
| P1 | 当前测试默认走内存实现，与生产路径不同 | Testcontainers 覆盖 PG/pgvector、ES、Redis、MinIO |
| P1 | 当前测试基线已有 2 个失败 | 实施前先固定并修复两项验收失败，禁止带红重构 |
| P2 | 大量裸 Map、手写 JSON 和可选 JDBC | 类型化 DTO/Repository/Jackson，编译期约束 |
| P2 | `modelrag-common` 含 Web/JDBC/Redis，无法直接做 SDK | 新增 framework-light `modelrag-api`，收敛 common |
| P2 | 原始正文重复存 PostgreSQL，删除/保留策略不完整 | 原文件/解析 artifact 进 MinIO，DB 保存元数据/chunk |
| P2 | outbox 串行、无租约回收/死信 | 批量 claim、lease、幂等、重试和死信运维入口 |
| P2 | 模型、prompt、索引配置缺少统一版本 | 请求 trace 记录 provider/model/prompt/index/config 版本 |

## 13. 测试与验收

### 13.1 测试分层

- 单元测试：`test` Profile 的显式 fake，仅验证领域规则，不伪装成生产集成测试。
- 集成测试：Testcontainers 启动 PostgreSQL/pgvector、Elasticsearch（含 SmartCN）、Redis、MinIO；模型用 WireMock/兼容 stub，夜间任务可选真实 Ollama。
- 契约测试：每类模型 provider 的流式、tool calling、JSON schema、错误映射和取消。
- 文档测试：使用第 7 节真实规模语料，校验解析结构、引用页码、批处理内存、失败恢复和重建一致性。
- 检索评测：固定 query/qrel，记录 exact 与 ANN 差异、融合前后指标、rerank 收益和延迟。
- 故障测试：停止 PG/ES/Redis/MinIO/模型服务，验证 fail-fast、降级标记、outbox 恢复和无脏 READY 状态。
- 性能/稳定性：并发上传、并发问答、长会话、6 步 Agent、24 小时 soak；检查线程池、连接池、堆、GC 和积压。

### 13.2 必过验收

- 不设置 `postgres` Profile 的默认启动仍连接 PostgreSQL；数据库不可用时启动或 readiness 明确失败。
- PostgreSQL 在请求期间中断时统一返回 `503 DEPENDENCY_UNAVAILABLE`，不得绕过数据库继续处理，也不得出现 JVM/数据库双写分裂。
- 全仓生产源码不存在会话/长期记忆/文档/审批的 JVM Map fallback。
- 重启应用后会话、最近消息、摘要和长期记忆完整，并只从 PostgreSQL 恢复。
- 两个用户和两个数据集之间的会话、密钥、文档、检索、记忆完全隔离。
- 普通 RAG、自动知识库选择和 Agent 都能正确处理至少两轮代词追问；清晰独立问题不调用改写模型。
- 第 12、24 条消息分别触发一次后台摘要；摘要失败不阻塞当前问答、可补偿，最近 8 条消息始终保留，且不重复摘要或计费。
- 明确“记住”、推断建议、确认、拒绝、修改、暂停、恢复、撤销、过期、导出和全部删除均可验证；模型回答中的错误事实不会自动成为长期记忆。
- 用户 A 的记忆不能被用户 B 检索；数据集 A 的业务事实不能进入数据集 B，用户回答偏好可以跨数据集。
- 50 MiB 边界文件不会因 `getBytes()` 造成整文件堆副本；超限、损坏、扫描 PDF 均给出稳定错误且不产生可查询脏数据。
- 文档重建期间旧版本可查询，新版本只有双索引完成后才切换。
- BGE-large-zh-v1.5 相关配置、模型下载、容器和代码引用为零；数据库向量维度统一为 1024。
- 普通 RAG 达到既定首 token/完整响应 p95；reranker 500ms 超时、Elasticsearch/pgvector 任一单路故障均按设计降级并暴露降级原因。
- Redis 停机时普通直接 RAG 仍可工作；需要幂等或副作用保障的 Agent/工具请求被明确拒绝。
- 伪造用户/角色 Header、URL 中长期 Token、SSRF 地址和缺少生产密钥启动均被阻止。
- ReAct 在重复调用、无新观察、默认步长 6、自定义 1～10、超时或取消时可预测结束，API/SSE 不出现原始 Thought。
- 在用户可访问 1、10、100 个知识库时，自动路由只做一次 Top3 预选和一次正式检索，不产生逐库完整检索。
- 当前全部 Maven/前端测试恢复为绿色，新增生产路径集成测试也为绿色。

## 14. 实施顺序

### 阶段 0：建立安全基线

1. 当前目录没有可用 Git 历史，先创建可恢复快照或初始化版本控制。
2. 固定现有 106 个测试的结果，修复 `M0AcceptanceTest.bootstrapEvalSetFromIndexedDocuments` 和 `M2AcceptanceTest.localRerankFallbackPrioritizesQuestionRelevantEvidence`。
3. 为核心 API、数据库 schema、延迟和检索质量记录基线。

### 阶段 1：单一持久化模型

1. PostgreSQL/Flyway 默认启用，迁移所有可选 JDBC 分支。
2. 内存实现移至测试目录；会话/消息/记忆统一 Repository。
3. 加入持久卷、Secret、health/readiness、备份与恢复脚本。

### 阶段 2：文档与索引重构

1. 接入 MinIO、`DocumentVectorizationTool`、parser registry 和安全扫描。
2. 实现结构化分块、窗口处理、批量 Embedding、索引版本状态机和可靠 outbox。
3. 换为 Qwen3-Embedding-0.6B/1024 维，全量重建并删除 BGE-large-zh-v1.5。
4. 建立真实规模文档 fixture 与大文件验收。

### 阶段 3：Spring AI 问答与检索

1. 建立 Model Gateway、BYOK、能力探测和 SecretProtector。
2. 升级 pgvector/ES mapping，完成向量 + BM25 + RRF + 条件 rerank + MMR。
3. 实现意图、问题改写、证据不足策略、引用和真正流式输出。
4. 拆分 `QaOrchestrator`，删除在线 A/B 与答案缓存。

### 阶段 4：记忆、Agent 与工具

1. 普通 RAG/Agent 接入统一数据库会话上下文和异步模型摘要。
2. 实现需确认的长期记忆、作用域、过期、查询/编辑/删除/导出。
3. 实现有界 ReAct、Java/HTTP Tools、审批、SSRF 防护、幂等和循环检测。

### 阶段 5：API、SDK 边界与验收

1. 建立 `modelrag-api`、`/api/v2`、类型化 DTO、验证和 OpenAPI，迁移前端。
2. 完成权限、限流、可观测性、故障注入、性能和离线质量回归。
3. 输出 SDK 打包清单；本轮不提前发布不稳定 SDK。

## 15. 实施门禁

每个阶段只有同时满足以下条件才能进入下一阶段：

- 数据迁移与回滚/重建路径已验证。
- 新增测试通过，已有测试没有新增失败。
- 没有把数据库事实重新放入本地缓存或 Redis。
- 日志中无密钥、完整记忆和大段文档正文。
- 可观测指标能够解释成功、降级、失败和耗时。
- 计划中的 P0 全部关闭；P1 未关闭项必须有明确 owner 和截止阶段。

完成以上计划后，系统具备可靠的数据库事实源、真实文档处理能力、可控的 Spring AI RAG/Agent 流程和稳定的公共接口，届时再将 `modelrag-api` 与自动配置层包装为 SDK/Starter，而不需要把服务器内部实现一起暴露。

## 16. 关键官方依据

- [Spring AI Getting Started](https://docs.spring.io/spring-ai/reference/getting-started.html)：Spring AI 2.0.x 支持 Spring Boot 4.0.x / 4.1.x，并通过 BOM 管理依赖版本。
- [Elasticsearch Smart Chinese Analysis plugin](https://www.elastic.co/docs/reference/elasticsearch/plugins/analysis-smartcn)：SmartCN 面向中文及中英混合文本；插件需要安装到每个 Elasticsearch 节点并重启，因此镜像和版本必须固定。
- [pgvector 官方文档](https://github.com/pgvector/pgvector)：近似索引在附带过滤条件时可能返回不足；0.8.0 起提供 iterative scan，并建议用 exact search 对照监控召回率。
