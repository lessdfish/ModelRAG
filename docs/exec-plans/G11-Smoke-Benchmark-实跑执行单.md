# ModelRAG G11 — Smoke Benchmark 实跑执行单

## 基线 Commit
`f5eb958eaeebfe5d1c1fd994621a7069411a7684`

## 目标
本轮不修改业务代码，只完成 G11 的真实 Smoke Benchmark（冒烟压测）和对应证据产物。

Smoke 规模：
- 20,000 RetrievalUnit（检索单元）
- 2,000 Active Document（有效文档）
- 1024 维向量
- 真实 PostgreSQL + pgvector
- 真实 Elasticsearch
- 真实 Java Benchmark Endpoint
- workload 并发 1 / 8 / 32

Smoke 只验证 Benchmark 链路和基本检索正确性，不能代替 Full 1M Benchmark，也不能因此进入 G12。

## 1. Preflight
Codex 先执行：

```powershell
git status --short
git rev-parse HEAD
java -version
python --version
docker version
```

要求：
- HEAD 必须等于 `f5eb958eaeebfe5d1c1fd994621a7069411a7684`
- Java major 必须为 21
- Docker / Python 可用
- 不清理、不 reset 用户已有修改
- 如果 HEAD 不一致，停止，不要 checkout/reset 用户代码

## 2. 重新生成当前 Commit 的 ACL Evidence

```powershell
mvn -pl modelrag-server -am `
  -Dtest=G112AclIntegrationTest `
  -Dsurefire.failIfNoSpecifiedTests=false test
```

确认：
`target/gate-evidence/acl-isolation.json`

必须：
- status=PASSED
- gitCommit=`f5eb958eaeebfe5d1c1fd994621a7069411a7684`

如果 Docker/Testcontainers 不可用，停止并报告。绝对不要手写假的 ACL evidence。

## 3. 使用隔离 Benchmark 环境
不要把 Smoke fixture 灌入日常开发数据库。

先检查：
- MODELRAG_BENCHMARK_DATABASE_URL
- MODELRAG_BENCHMARK_ES_URL
- MODELRAG_BENCHMARK_TARGET

如果已有明确的专用 Benchmark 环境，优先复用。

如果没有：
- 检查本机 Docker 和仓库已有基础设施配置
- 可以启动临时隔离的 PostgreSQL+pgvector 和 Elasticsearch
- 使用独立容器名、独立端口
- 不删除/覆盖用户已有容器、数据库或索引
- 不确定兼容版本时先依据项目现有配置/测试/本地镜像确定，不要瞎猜

## 4. 生成 Smoke Fixture

```powershell
$SmokeDir = Join-Path $env:TEMP "modelrag-retrieval-smoke"

python benchmarks/retrieval-scale/generate/generate_dataset.py `
  --mode smoke `
  --output $SmokeDir
```

检查 `$SmokeDir/manifest.json`：
- mode=smoke
- 20,000 retrieval units
- 2,000 active documents

不要修改 manifest 数字。

## 5. Load 到真实 PG/pgvector + ES

```powershell
python benchmarks/retrieval-scale/load/load_all.py `
  --manifest "$SmokeDir\manifest.json" `
  --database-url $env:MODELRAG_BENCHMARK_DATABASE_URL `
  --elasticsearch-url $env:MODELRAG_BENCHMARK_ES_URL
```

Loader 必须真实成功。

如果 dataset 已存在，不要删除用户数据，换专用空 Benchmark 环境。

## 6. 启动真实 Java Benchmark Runtime
必须保证：
- Java 21
- Spring profile=`benchmark`
- 数据库=上述专用 Benchmark PostgreSQL
- Elasticsearch=上述专用 Benchmark ES
- `MODELRAG_EVAL_BASE_COMMIT=f5eb958eaeebfe5d1c1fd994621a7069411a7684`

需要本地 secret 时，只使用临时值，不打印、不提交。

如果没有可运行 jar，只执行一次：

```powershell
mvn -pl modelrag-server -am -DskipTests package
```

然后启动 `modelrag-server`，并传入：

```text
--spring.profiles.active=benchmark
```

确认：
- `/actuator/health`
- `http://127.0.0.1:8091/internal/benchmarks/retrieval`

都来自当前 Commit 的 Java 21 Server。

不要为了启动 Benchmark 修改业务源码。

## 7. 执行真实 Smoke

```powershell
$env:MODELRAG_BENCHMARK_TARGET = "http://127.0.0.1:8091/internal/benchmarks/retrieval"

python benchmarks/retrieval-scale/run/benchmark.py `
  --mode smoke `
  --manifest "$SmokeDir\manifest.json" `
  --requests-per-workload 100 `
  --report-dir benchmarks/retrieval-scale/reports
```

必须真实执行仓库定义 workload：
- semantic-only
- lexical-only
- hybrid
- document-scoped
- broad
- stale-build-exclusion
- high-active-build-count

如果 Runner 输出 `NOT RUN`：
- 保留准确原因
- 停止
- 不要修改报告让它变成 COMPLETED

## 8. Smoke Report 验收
检查新生成的 JSON report：

必须：
- status=COMPLETED
- mode=smoke
- gitCommit=`f5eb958eaeebfe5d1c1fd994621a7069411a7684`
- runtime.serverJavaVersion 主版本=21

Correctness 至少：
- noStaleBuildLeakage=true
- noActiveBuildTruncation=true
- boundedResults=true
- validEvidence=true

同时记录：
- p50
- p95
- p99
- errorRate
- degradedRate
- timeoutRate
- 各 workload 结果

注意：Smoke 只有 2,000 active documents，不能替代 Full 1M 对 >10,000 active build overflow 的最终证明。

## 9. 禁止事项
本轮禁止：
- 修改 Java/Python 业务源码来让 Benchmark 通过
- 修改 threshold 美化结果
- 把 Smoke 叫作 Full
- 伪造 benchmark JSON
- 伪造 ACL evidence
- 删除/覆盖用户日常数据库
- 执行 G12
- 切 V2 默认
- 自动执行根目录 `mvn test`

如果发现代码 Bug：
输出 `CODE ISSUE FOUND` 并停止，不擅自修代码。

## 10. 最终报告模板

```text
G11 SMOKE BENCHMARK EXECUTION REPORT

Commit:
f5eb958eaeebfe5d1c1fd994621a7069411a7684

Preflight:
- git HEAD:
- Java:
- Python:
- Docker:
- workspace status:

ACL Evidence:
- status:
- artifact:
- artifact gitCommit:

Benchmark Infrastructure:
- PostgreSQL/pgvector:
- Elasticsearch:
- server profile:
- server Java:
- endpoint:
- fixture directory:

Fixture:
- mode:
- active documents:
- retrieval units:
- vector dimension:

Loader:
- PostgreSQL load:
- Elasticsearch load:
- preflight verification:

Smoke:
- status:
- report path:
- benchmark gitCommit:
- serverJavaVersion:
- p50:
- p95:
- p99:
- errorRate:
- degradedRate:
- timeoutRate:

Correctness:
- noStaleBuildLeakage:
- noActiveBuildTruncation:
- boundedResults:
- validEvidence:

Workloads:
- semantic-only:
- lexical-only:
- hybrid:
- document-scoped:
- broad:
- stale-build-exclusion:
- high-active-build-count:

Result:
SMOKE PASS / SMOKE FAIL / NOT RUN

Full 1M:
NOT RUN

Readiness:
NOT_READY_FOR_G12

Source code changed:
NO

G12 started:
NO
```

Smoke PASS 也不要宣称 READY_FOR_G12，因为 Full 1M 还没跑。
