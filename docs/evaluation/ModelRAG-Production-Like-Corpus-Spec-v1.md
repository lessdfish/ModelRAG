# ModelRAG Production-like Evaluation Corpus Spec v1

## 1. 目的

本规范用于生成一套类生产企业知识库语料，供 ModelRAG 做 Demo、检索回归、Evidence 评测、版本隔离测试和 Agentic Retrieval 测试。它不是“随机长文档集合”，而是一套有明确 Ground Truth（标准事实）、Hard Negative（困难干扰项）、Version Conflict（版本冲突）、Cross-document Evidence（跨文档证据）和 Unanswerable（不可回答问题）的可评测语料。

第一版目标：

```text
企业背景：虚构软件与 AI 公司 NovaTech 星云科技
员工规模：约 2000 人
主文档数：30
正文规模：约 80~150 万中文字符
核心 Fact：100
主评测问题：150
不可回答问题：20
格式：PDF / DOCX / XLSX / Markdown
```

## 2. 设计原则

1. 先定义事实与正确 Evidence，再生成正文。
2. 文档之间必须存在引用、覆盖、版本替代、相似内容和跨部门关系。
3. 不允许所有文档都写成相同 AI 文风。
4. 长文档必须具有真实章节层级、定义、职责、例外、附件和引用文件。
5. 短通知允许很短，但可以拥有更高规则优先级。
6. Excel 用于职级、城市、金额、SLA、数据等级等结构化事实。
7. FAQ 使用口语化表达，但正式答案应尽量指向正式制度。
8. 同一个主题必须存在 Hard Negative，例如“权限”同时出现在 OA、GitLab、数据库、生产环境、采购审批等文档。
9. 历史版本不能删除，它们用于验证 stale/version isolation。
10. 所有公司、人名、制度、金额、错误码均为虚构测试数据，不复制真实公司的内部资料。

## 3. 目录结构

```text
datasets/
└─ production-like-corpus/
   ├─ corpus/
   │  ├─ hr/
   │  ├─ finance/
   │  ├─ it/
   │  ├─ security/
   │  ├─ procurement/
   │  ├─ legal/
   │  └─ engineering/
   ├─ evaluation/
   │  ├─ questions.jsonl
   │  ├─ expected_evidence.jsonl
   │  ├─ unanswerable.jsonl
   │  └─ categories.json
   ├─ metadata/
   │  ├─ manifest.json
   │  ├─ fact_registry.json
   │  ├─ document_relations.json
   │  └─ version_graph.json
   ├─ generator/
   │  ├─ README.md
   │  ├─ generate.py
   │  └─ templates/
   └─ README.md
```

## 4. 文档身份模型

正式制度类文档应尽量包含：

```text
documentId
documentCode
title
version
status
confidentiality
ownerDepartment
author
reviewer
approver
publishDate
effectiveDate
supersedes
references
revisionHistory
```

示例：

```text
文档编号：NT-HR-POL-2026-003
文档名称：员工休假与考勤管理办法
版本：V4.0
状态：生效
密级：内部
归口部门：人力资源中心
发布日期：2026-01-15
生效日期：2026-02-01
替代文件：NT-HR-POL-2024-003 V3.1
```

FAQ、Excel、短通知不强制使用完整外壳，但仍必须有文档 ID、版本/日期和归口部门。

# 5. 30 份文档

## 5.1 HR

### HR-001 员工手册 2026
- 格式：DOCX；45~55 页；ACTIVE
- 作用：总纲、Document Routing、跨文档引用
- 章节：入职、考勤、薪酬、假期、绩效、行为规范、资产与信息保护、离职
- 只说明总原则，细节引用 HR-003、HR-004
- 关系：REFERENCES HR-003/HR-004；SUPERSEDES HR-002

### HR-002 员工手册 2024
- 格式：PDF；40~45 页；SUPERSEDED
- 作用：历史版本隔离
- 必须包含旧事实：陪产假10天、健康补贴800元等
- 与 HR-001 高度相似，但不得完全相同

### HR-003 员工休假与考勤管理办法
- 格式：PDF；30~35 页
- 作用：Semantic、Hybrid、Structure
- 章节：工作时间、考勤、迟到早退、年假、病假、婚假、产假、陪产假、育儿假、丧假、事假、申请、特殊情况
- 必须包含正式措辞“男性员工配偶依法分娩”

### HR-004 薪酬福利与补贴管理制度
- 格式：DOCX；25~30 页
- 作用：Hard Negative
- 内容：薪酬结构、奖金、餐补、交通补贴、健康补贴、异地派驻补助
- 必须与差旅住宿标准形成相似但不同概念

### HR-005 HR常见问题与员工自助指南
- 格式：Markdown；300~400 Q&A
- 风格：口语化；经常引用正式制度，不重复完整条款

## 5.2 Finance

### FIN-001 差旅及业务出行管理办法 2026
- 格式：PDF；35~40 页
- 作用：Hybrid、一般规则+例外规则
- 章节：出差申请、审批、交通、住宿、餐费、市内交通、海外差旅、报销、特殊情况
- 必须包含 P7 高铁一般规则与 >6小时+VP 事前审批例外

### FIN-002 2026差旅费用标准
- 格式：XLSX
- Sheet：城市等级、国内住宿标准、餐费标准、海外地区、特殊区域
- 作用：Table Retrieval
- 必须可从“上海=A类城市”+“P6/P7 A类=600元”推导答案

### FIN-003 2026 Nova AI Summit 差旅标准临时调整通知
- 格式：DOCX 或 PDF；2~4 页
- 作用：短文档高优先级 Override
- 有效期：2026-11-10 至 2026-11-18
- 上海住宿临时提高至800元
- 明确“其他交通规则仍按 FIN-001 执行”

### FIN-004 费用报销管理办法
- 格式：PDF；25~30 页
- 作用：与差旅标准相似但不同
- 内容：报销时限、发票、电子发票、丢失发票、公司卡、个人垫付、超标准处理

### FIN-005 财务报销 FAQ
- 格式：Markdown；约200 Q&A
- 风格：口语化，例如“酒店超了100块怎么办？”

## 5.3 IT

### IT-001 IT服务管理与员工使用手册
- 格式：PDF；55~65 页
- 作用：大型入口文档、Routing
- 章节：账号、邮箱、SSO、VPN、网络、打印、软件安装、GitLab、Jira、资产申请

### IT-002 VPN使用与故障处理手册
- 格式：PDF；35~45 页
- 作用：Semantic + Procedure
- 章节：安装、MFA、设备证书、连接流程、常见故障、日志、升级

### IT-003 IT错误码与故障代码参考手册
- 格式：PDF；60~80 页
- 作用：BM25/Exact Match
- 必须包含大量相似错误码：VPN-ERR-1042 / 1043、SSO-ERR-2107、MAIL-ERR-3021

### IT-004 账号、角色与权限管理规范
- 格式：DOCX；25~30 页
- 作用：权限主题 Hard Negative
- 覆盖 OA、GitLab、数据库、普通系统权限，但不能替代生产敏感数据审批

### IT-005 终端设备与软件安装规范
- 格式：PDF；20~25 页
- 作用：与 Security 交叉
- 内容：管理员权限、软件白名单、USB、磁盘加密、设备遗失、维修与更换

## 5.4 Security

### SEC-001 信息安全管理制度
- 格式：PDF；50~60 页
- 作用：总纲
- 覆盖账号、设备、数据、远程办公、第三方、安全事件、访问控制、审计

### SEC-002 数据分类分级标准
- 格式：XLSX
- 作用：Table Retrieval
- 等级：L1/L2/L3/L4
- 示例：通讯录=L2、未公开财务=L3、客户身份证=L3、生产数据库密钥=L4
- 包含允许存储位置、传输规则、加密要求

### SEC-003 数据泄露与安全事件应急响应预案
- 格式：DOCX；30~40 页
- 作用：Semantic + Procedure
- 章节：发现、报告、隔离、证据保全、升级、调查、恢复、复盘

### SEC-004 生产环境与敏感数据访问控制规范
- 格式：PDF；30~35 页
- 作用：生产数据库权限与普通 IT 权限区分
- 内容：只读、写权限、临时权限、Break Glass、DBA、System Owner、审计、自动失效

### SEC-005 安全事件 FAQ
- 格式：Markdown；150~200 Q&A
- 风格：电脑丢了、U盘丢了、发错客户数据、怀疑账号被盗

## 5.5 Procurement / Legal

### PROC-001 采购管理制度
- 格式：PDF；35~40 页
- 内容：申请、询价、比价、招标、单一来源、预算、审批、验收、付款

### PROC-002 供应商准入与生命周期管理办法
- 格式：DOCX；25~30 页
- 内容：初审、资质、安全审查、财务审查、黑名单、年度复核、退出

### PROC-003 采购审批权限矩阵
- 格式：XLSX
- 作用：金额区间与审批链 Table Retrieval
- 必须包含 5万、20万、100万边界

### LEG-001 合同管理制度
- 格式：PDF；30~35 页
- 内容：模板、法务审核、非标条款、签署、印章、归档、续签、终止

### LEG-002 合同与采购常见问题
- 格式：Markdown；150~200 Q&A
- 部分答案必须同时依赖采购制度与合同制度

## 5.6 Engineering / Operations

### ENG-001 研发项目生命周期管理规范
- 格式：PDF；45~50 页
- 内容：需求、立项、设计、开发、测试、发布、验收、归档

### OPS-001 生产变更管理规范
- 格式：DOCX；35~40 页
- 内容：普通变更、标准变更、紧急变更、风险评估、审批、灰度、回滚、窗口、失败处理

### OPS-002 生产事故应急响应手册
- 格式：PDF；55~65 页
- 作用：Agentic Navigation
- 章节：事故等级、P0、数据库故障、网络故障、服务故障、回滚失败、指挥、沟通、恢复、复盘、升级矩阵

### OPS-003 服务等级与生产事故等级标准
- 格式：XLSX
- 作用：P0/P1/P2、响应时间、升级时间 Table Retrieval

### OPS-004 生产环境值班与升级通讯手册
- 格式：PDF；20~25 页
- 使用角色名而非真人：Incident Commander、DBA On-call、Network On-call、Security On-call、Business Owner

### OPS-005 研发与运维 FAQ
- 格式：Markdown；约250 Q&A
- 风格：口语化生产故障、变更、回滚问题


# 6. Document Relations

必须生成 `document_relations.json`，至少包含：

```text
HR-001 REFERENCES HR-003
HR-001 REFERENCES HR-004
HR-001 SUPERSEDES HR-002

FIN-001 REFERENCES FIN-002
FIN-001 RELATED FIN-004
FIN-003 OVERRIDES FIN-002 [2026-11-10, 2026-11-18]

IT-001 REFERENCES IT-002
IT-001 REFERENCES IT-003
IT-001 REFERENCES IT-004

SEC-001 REFERENCES SEC-002
SEC-001 REFERENCES SEC-003
SEC-001 REFERENCES SEC-004

PROC-001 REFERENCES PROC-003
PROC-001 REFERENCES PROC-002
PROC-001 RELATED LEG-001

OPS-001 RELATED OPS-002
OPS-002 REFERENCES OPS-003
OPS-002 REFERENCES OPS-004
```

若当前 NodeEdge 不支持 `OVERRIDES`，metadata 中仍保存业务关系；映射到图关系时可使用 SUPERSEDES/RELATED + effectiveDate。

# 7. 100 个核心 Fact Registry

## HR 15

| ID | Fact | Source | Retrieval |
|---|---|---|---|
| HR-001 | 当前陪产假15个自然日 | HR-003 | Semantic+Version |
| HR-002 | 2024旧制度陪产假10天 | HR-002 | Stale |
| HR-003 | 年假按累计工作年限分5/10/15天 | HR-003 | Rule |
| HR-004 | 新员工满足制度条件后才可使用当年度年假 | HR-003 | Conditional |
| HR-005 | 连续病假超过3天需要医疗证明 | HR-003 | Hybrid |
| HR-006 | 婚假须在登记后12个月内使用 | HR-003 | Exact condition |
| HR-007 | 育儿假按子女年龄和适用地区规则执行 | HR-003 | Semantic |
| HR-008 | 迟到30分钟以上进入异常考勤流程 | HR-003 | Rule |
| HR-009 | 忘打卡可在2个工作日内补卡 | HR-003/005 | FAQ→Policy |
| HR-010 | 紧急请假允许事后补申请 | HR-003 | Exception |
| HR-011 | 连续请假超过5个工作日需二级审批 | HR-003 | Multi-condition |
| HR-012 | 2026健康补贴1200元 | HR-004 | Version |
| HR-013 | 2024健康补贴800元 | HR-002 | Stale |
| HR-014 | 异地派驻住宿补贴不等于普通差旅住宿报销 | HR-004 | Hard Negative |
| HR-015 | 离职员工权限在最后工作日完成回收 | HR-001/IT-004 | Cross-domain |

## Finance 18

| ID | Fact | Source | Retrieval |
|---|---|---|---|
| FIN-001 | P7及以下高铁原则二等座 | FIN-001 | Hybrid |
| FIN-002 | P8/P9可乘高铁一等座 | FIN-001 | Rule |
| FIN-003 | P7铁路行程超过6小时且VP事前批准可一等座 | FIN-001 | Exception |
| FIN-004 | 上海属于A类城市 | FIN-002 | Table |
| FIN-005 | P6/P7上海住宿标准600元/晚 | FIN-002 | Table |
| FIN-006 | AI Summit期间上海住宿临时提高至800元/晚 | FIN-003 | Override |
| FIN-007 | 临时800元仅2026-11-10至11-18有效 | FIN-003 | Time |
| FIN-008 | 临时住宿通知不改变交通工具标准 | FIN-003 | Cross-doc |
| FIN-009 | 超住宿标准原则上只报标准内部分 | FIN-001/004 | Rule |
| FIN-010 | 特殊超标准需要额外审批 | FIN-001/004 | Exception |
| FIN-011 | 国内差旅餐补按自然日计算 | FIN-001/002 | Rule |
| FIN-012 | 发票须在规定报销周期内提交 | FIN-004 | Rule |
| FIN-013 | 电子发票必须避免重复报销 | FIN-004 | Keyword |
| FIN-014 | 发票遗失需要替代证明材料 | FIN-004 | Semantic |
| FIN-015 | 个人垫付和公司卡流程不同 | FIN-004 | Similarity |
| FIN-016 | 长期派驻住宿补贴不适用普通出差标准 | HR-004/FIN-001 | Hard Negative |
| FIN-017 | 海外住宿按国家/地区标准表执行 | FIN-002 | Table Routing |
| FIN-018 | 差旅费用标准以FIN-002为基准，而非员工手册 | FIN-001/002 | Routing |

## IT 15

| ID | Fact | Source | Retrieval |
|---|---|---|---|
| IT-001 | VPN-ERR-1042=证书链校验失败 | IT-003 | BM25 |
| IT-002 | VPN-ERR-1043=设备证书过期 | IT-003 | BM25 |
| IT-003 | SSO-ERR-2107=MFA Token校验失败 | IT-003 | BM25 |
| IT-004 | MAIL-ERR-3021=邮箱容量达到限制 | IT-003 | BM25 |
| IT-005 | 证书链失败需重新签发设备证书 | IT-002 | Semantic |
| IT-006 | VPN证书过期需走续签流程 | IT-002 | Similarity |
| IT-007 | VPN故障先查本地网络，再认证，再证书 | IT-002 | Procedure |
| IT-008 | 普通员工不得长期持有本地管理员权限 | IT-005 | Rule |
| IT-009 | 软件安装须符合白名单 | IT-005 | Keyword |
| IT-010 | GitLab权限由项目Owner审批 | IT-004 | Similarity |
| IT-011 | OA普通权限由部门管理员处理 | IT-004 | Similarity |
| IT-012 | 生产数据库权限不遵循普通OA权限流程 | IT-004/SEC-004 | Cross-doc |
| IT-013 | 离职账号必须统一回收 | IT-004 | Cross-domain |
| IT-014 | 设备丢失需同时通知IT与Security | IT-005/SEC-005 | Cross-doc |
| IT-015 | USB使用受信息安全等级约束 | IT-005/SEC-002 | Cross-doc |

## Security 15

| ID | Fact | Source | Retrieval |
|---|---|---|---|
| SEC-001 | 数据分L1/L2/L3/L4 | SEC-002 | Table |
| SEC-002 | 员工通讯录属于L2 | SEC-002 | Table |
| SEC-003 | 未公开财务数据属于L3 | SEC-002 | Table |
| SEC-004 | 客户身份证数据属于L3 | SEC-002 | Table |
| SEC-005 | 生产数据库密钥属于L4 | SEC-002 | Table |
| SEC-006 | L3/L4数据要求受控存储和加密 | SEC-002/001 | Cross-table |
| SEC-007 | 疑似L3/L4泄露15分钟内报告SOC | SEC-003 | Semantic |
| SEC-008 | 发错客户身份证表属于非授权披露 | SEC-003 | Semantic |
| SEC-009 | 安全事件先隔离风险并保留证据 | SEC-003 | Procedure |
| SEC-010 | 临时生产库只读需System Owner+DBA审批 | SEC-004 | Hybrid |
| SEC-011 | 生产写权限要求更高级审批 | SEC-004 | Similarity |
| SEC-012 | Break Glass仅用于紧急生产事件 | SEC-004 | Exact concept |
| SEC-013 | Break Glass必须事后审计 | SEC-004 | Cross-section |
| SEC-014 | 临时生产权限自动失效 | SEC-004 | Rule |
| SEC-015 | 普通IT账号权限不能替代生产数据访问审批 | IT-004/SEC-004 | Hard Negative |

## Procurement / Legal 14

| ID | Fact | Source | Retrieval |
|---|---|---|---|
| PROC-001 | ≤5万元采购仅部门负责人审批 | PROC-003 | Table |
| PROC-002 | 5~20万元增加VP审批 | PROC-003 | Table |
| PROC-003 | 20~100万元增加CFO审批 | PROC-003 | Table |
| PROC-004 | >100万元增加CEO审批 | PROC-003 | Table |
| PROC-005 | 85万元软件采购需负责人+VP+CFO | PROC-003 | Derived |
| PROC-006 | 一般采购应经过询价/比价 | PROC-001 | Rule |
| PROC-007 | 特定条件可单一来源采购 | PROC-001 | Exception |
| PROC-008 | 供应商准入需资质审核 | PROC-002 | Cross-doc |
| PROC-009 | 高风险供应商需安全审查 | PROC-002 | Cross-doc |
| PROC-010 | 黑名单供应商不得正常准入 | PROC-002 | Rule |
| LEG-001 | 非标准合同条款需要法务审核 | LEG-001 | Hybrid |
| LEG-002 | 合同签署前原则上需完成供应商准入 | LEG-001/PROC-002 | Cross-doc |
| LEG-003 | 合同金额权限与采购审批不是同一个流程 | LEG-001/PROC-003 | Hard Negative |
| LEG-004 | 已提供服务不代表可以绕过补签审批 | LEG-002/001 | FAQ→Policy |

## Engineering / Operations 23

| ID | Fact | Source | Retrieval |
|---|---|---|---|
| OPS-001 | P0=核心业务整体不可用 | OPS-003 | Table |
| OPS-002 | P0首次响应≤5分钟 | OPS-003 | Table |
| OPS-003 | P0技术负责人≤10分钟介入 | OPS-003 | Table |
| OPS-004 | P0每15分钟同步状态 | OPS-003 | Table |
| OPS-005 | P1首次响应≤10分钟 | OPS-003 | Similarity |
| OPS-006 | 普通生产变更必须事前审批 | OPS-001 | Rule |
| OPS-007 | P0恢复允许紧急变更流程 | OPS-001/002 | Exception |
| OPS-008 | 紧急变更仍需事后补审 | OPS-001 | Rule |
| OPS-009 | 高风险变更必须有回滚方案 | OPS-001 | Hybrid |
| OPS-010 | 回滚失败需升级Incident Commander | OPS-002/004 | Structure |
| OPS-011 | 数据库事故需DBA On-call介入 | OPS-002/004 | Structure |
| OPS-012 | 网络事故需Network On-call介入 | OPS-002/004 | Structure |
| OPS-013 | 安全事故需Security On-call介入 | OPS-002/004 | Structure |
| OPS-014 | P0必须指定Incident Commander | OPS-002 | Cross-section |
| OPS-015 | 事故恢复后需复盘 | OPS-002 | Rule |
| OPS-016 | 复盘需记录根因与改进项 | OPS-002 | Rule |
| OPS-017 | 发布前必须完成测试与审批 | ENG-001 | Lifecycle |
| OPS-018 | 高风险发布必须灰度 | ENG-001/OPS-001 | Rule |
| OPS-019 | 普通变更窗口与紧急变更不同 | OPS-001 | Similarity |
| OPS-020 | 数据库慢但未导致核心业务不可用不当然属于P0 | OPS-002/003 | Semantic |
| OPS-021 | 核心支付整体不可用属于P0 | OPS-003 | Semantic |
| ENG-001 | 项目需经历需求→设计→开发→测试→发布 | ENG-001 | Structure |
| ENG-002 | 未完成验收不能直接归档为完成 | ENG-001 | Rule |


# 8. Evaluation Set

主评测共150题：

| Category | 数量 |
|---|---:|
| SEMANTIC | 35 |
| KEYWORD_BM25 | 25 |
| HYBRID | 30 |
| CROSS_DOCUMENT | 20 |
| VERSION_CONFLICT | 15 |
| TABLE | 15 |
| STRUCTURE_AGENTIC | 10 |
| TOTAL | 150 |

Category 表示主测试意图，不代表执行时只能使用该检索方式。同一个 Fact 可以生成多个不同问法，但不能只做机械同义词替换，应改变用户视角、上下文和条件。

例如：

```text
HR-001:
1. 陪产假多少天？                      KEYWORD
2. 我老婆下周生产，我能休多久？        SEMANTIC
3. 2024说10天，现在还是10天吗？        VERSION_CONFLICT
4. 老婆生孩子我能休多久，系统怎么申请？ CROSS_DOCUMENT
```

# 9. Question JSONL Schema

`evaluation/questions.jsonl` 每行：

```json
{
  "id": "EVAL-FIN-HYB-001",
  "query": "我是P7，去上海出差酒店一晚最多报多少？",
  "category": "HYBRID",
  "difficulty": "MEDIUM",
  "factIds": ["FIN-004", "FIN-005"],
  "expectedAnswer": "600元/晚",
  "expectedDocuments": ["FIN-002"],
  "expectedSections": ["城市等级", "国内住宿标准"],
  "requiredEvidence": [
    "上海属于A类城市",
    "P6-P7在A类城市住宿标准为600元/晚"
  ],
  "forbiddenEvidence": [],
  "hardNegatives": ["HR-004"],
  "notes": "需要先确定城市等级，再查职级标准。"
}
```

Version 题示例：

```json
{
  "id": "EVAL-HR-VER-001",
  "query": "2024年我记得陪产假只有10天，现在还是这样吗？",
  "category": "VERSION_CONFLICT",
  "factIds": ["HR-001", "HR-002"],
  "expectedAnswer": "不是，当前规则为15个自然日。",
  "expectedDocuments": ["HR-003"],
  "requiredEvidence": ["当前陪产假15个自然日"],
  "forbiddenEvidence": [
    {
      "documentId": "HR-002",
      "fact": "陪产假10天"
    }
  ]
}
```

# 10. Evidence Ground Truth

额外生成 `expected_evidence.jsonl`，至少包含：

```text
questionId
factIds
requiredDocumentIds
requiredSectionPaths
requiredStatements
forbiddenDocumentIds
minimumEvidenceCount
requiresAllEvidence
```

Cross-document 题必须 `requiresAllEvidence=true`。

典型题：

```text
Query:
P7，11月15日参加上海AI Summit，高铁一等座和750元酒店能不能都报？

Required facts:
FIN-001
FIN-006
FIN-007
FIN-008

Expected:
酒店750可在临时800标准内报销；
一等座不能仅因为临时住宿通知而报销，
除非满足FIN-003的6小时+VP事前审批条件。
```

如果只找到“酒店800”，Evidence 不完整，应判失败。

# 11. Hard Negative 设计

每个核心领域至少设计 5 组强干扰。

### 权限
```text
OA权限
GitLab权限
普通数据库权限
生产数据库只读权限
生产数据库写权限
采购审批权限
```

### 住宿
```text
普通差旅住宿
长期派驻住宿补贴
驻场补助
海外住宿
会议临时住宿标准
```

### 假期
```text
产假
陪产假
育儿假
病假
婚假
事假
```

### 事故
```text
P0
P1
数据库性能下降
核心业务不可用
安全事件
普通故障
```

### 采购/合同
```text
采购审批
合同签署权限
供应商准入
付款审批
单一来源例外
```

正文中必须自然重复这些关键词，不能为了评测而刻意写得过于明显。

# 12. Unanswerable Set

额外生成20题 `evaluation/unanswerable.jsonl`，知识库中不得存在足够 Evidence。

示例：

```text
公司2027年的陪产假政策是什么？
CEO今年个人奖金是多少？
下个月还会不会继续提高上海住宿标准？
某员工昨天请了什么假？
2027年P0响应标准会调整吗？
```

每题记录：

```text
id
query
reasonUnanswerable
confusingDocuments
expectedBehavior = "INSUFFICIENT_EVIDENCE"
```

# 13. 三层 Ground Truth

评测至少区分：

## Retrieval Ground Truth
- Doc Recall
- Section Recall
- RetrievalUnit Recall
- stale document leakage

## Evidence Ground Truth
- required evidence completeness
- forbidden evidence
- version correctness
- cross-document completeness

## Answer Ground Truth
- factual correctness
- faithfulness
- unsupported claim
- insufficient-evidence behavior

不得只以最终 Answer 是否包含关键词作为唯一评判。

# 14. 文档写作要求

制度：正式、规范，包含“应/不得/原则上/特殊情形除外”，有编号和层级。

IT/运维手册：采用“现象/原因/检查/处理/回滚/升级”等技术说明结构。

FAQ：口语化，问题短，答案简洁并常引用正式文档。

通知：短、日期和适用范围明确，可以覆盖一般规则。

XLSX：避免把所有事实同时复制成 Markdown；表格本身必须成为主要来源。

# 15. 内容规模与噪声

目标总正文约80~150万中文字符。长文档不得通过重复句子机械灌水，应加入真实企业文档常见但不一定直接回答问题的内容：

```text
目的
适用范围
术语
职责
流程说明
例外
修订记录
引用文件
附录
背景说明
表格
审批说明
操作注意事项
```

允许适量重复术语和相似章节，这是检索难度的一部分。

# 16. 文件生成要求

应以可重复脚本生成全部文件，推荐：

```text
DOCX -> python-docx
PDF  -> ReportLab 或稳定的 HTML/DOCX 转 PDF
XLSX -> openpyxl
MD   -> UTF-8
```

生成过程必须可重复执行、固定随机 seed，并生成 manifest。不要依赖在线 LLM API 才能重新构建语料；正文应由预定义模板和内容模块生成。

# 17. manifest.json

每份文件至少记录：

```json
{
  "documentId": "FIN-001",
  "filename": "差旅及业务出行管理办法_2026.pdf",
  "format": "PDF",
  "department": "FINANCE",
  "version": "V3.2",
  "status": "ACTIVE",
  "effectiveDate": "2026-03-01",
  "supersedes": [],
  "references": ["FIN-002", "FIN-004"],
  "factIds": ["FIN-001", "FIN-002", "FIN-003"],
  "evaluationTags": ["HYBRID", "EXCEPTION", "CROSS_DOCUMENT"]
}
```

# 18. Quality Gates

生成完成必须自动检查：

1. 文档数=30。
2. Fact Registry=100。
3. 主评测题=150。
4. Unanswerable=20。
5. 每个 Fact 至少有一个主来源。
6. 每道主评测题必须引用存在的 Fact ID。
7. 每道题 expectedDocuments 必须存在于 manifest。
8. Version Conflict 题必须有 forbiddenEvidence 或 superseded source。
9. Cross-document 题至少需要2个文档或2个独立 Evidence。
10. TABLE 题必须至少引用一个 XLSX。
11. KEYWORD_BM25 题中至少一部分必须包含错误码、制度编号或精确标识符。
12. STRUCTURE_AGENTIC 题必须来自长文档不同章节。
13. 所有 ACTIVE/SUPERSEDED 关系不能自相矛盾。
14. 生成文件必须能正常打开。
15. XLSX 必须至少有一个非默认 Sheet 和真实结构化表格。
16. PDF/DOCX 不得全部只有标题+几段短文。
17. 正文中不得出现“这是测试数据”“为了RAG测试”等破坏真实感的措辞。

# 19. 推荐首批人工抽查题

1. 我老婆下周生产，我能休多久？
2. VPN-ERR-1042怎么处理？
3. P7去上海出差，一晚酒店最多报多少？
4. P7在11月15日参加AI Summit，750酒店能报吗？
5. P7同一天坐高铁一等座也能报吗？
6. 我只想临时查一下生产库，需要谁审批？
7. 客户身份证表发错群了第一步怎么办？
8. 采购85万元软件需要哪些人批准？
9. 支付系统全挂了，几分钟内必须响应？
10. 数据库变更导致P0且回滚失败，下一步升级给谁？

这些题应分别体现 Semantic、BM25、Hybrid、Override、Hard Negative、Table、Cross-document、Structure/Agentic 的差异。

# 20. v1 完成定义

```text
30 files generated
100 facts registered
150 answerable evaluation questions
20 unanswerable questions
manifest/document relations/version graph generated
all quality gates pass
all files open successfully
generator rerunnable with fixed seed
```

完成后进入：

```text
Corpus Import
→ ModelRAG ingestion
→ Retrieval-only evaluation
→ Vector/BM25/Hybrid/Rerank comparison
→ Evidence evaluation
→ Agentic Retrieval evaluation
```
