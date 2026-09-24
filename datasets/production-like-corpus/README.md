# ModelRAG Production-like Evaluation Corpus v1.1

该语料模拟 NovaTech 星云科技内部知识库，包含制度、操作手册、短通知、FAQ 和结构化标准表。

语料的唯一事实来源是 `docs/evaluation/ModelRAG-Production-Like-Corpus-Spec-v1.1.md`。历史文件、现行制度和临时覆盖通知均被保留，用于检索、Evidence 完整性、版本隔离、表格检索和跨章节导航评测。

目录说明：

- `corpus/`：按业务域组织的 30 份文件。
- `metadata/`：文档身份、100 个核心 Fact、文档关系和版本图。
- `evaluation/`：150 道可回答题及 20 道不可回答题。
- `generator/`：固定 seed 的离线生成器与质量检查。

本目录是 corpus 产物，不包含数据库导入或 ModelRAG 运行状态变更。

