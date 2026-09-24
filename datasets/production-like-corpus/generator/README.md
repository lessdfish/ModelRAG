# Production-like Corpus Generator

此目录包含 ModelRAG Production-like Evaluation Corpus v1.2 的离线、固定 seed 生成器。

## 运行

使用 Python 3.12，并安装 `python-docx`、`openpyxl`、`reportlab` 和 `pypdf`：

```powershell
python generator/generate.py --clean-generated
python generator/quality_check.py --write-report
```

固定 seed 为 `20260917`。生成器从
`docs/evaluation/ModelRAG-Production-Like-Corpus-Spec-v1.1.md` 读取 100 个 Fact，并使用显式 sourceLocations 将事实写入对应章节。正文、评测题和关系定义不依赖在线模型服务。

可使用 `--output-root` 在临时目录重建 corpus，并比较所有生成文件的 SHA-256，以验证可重复性。

## 输出

- `corpus/`：30 份 PDF、DOCX、XLSX、Markdown 文件。
- `metadata/`：manifest、Fact Registry、文档关系、版本图和质量报告。
- `evaluation/`：150 道主评测题、Evidence Ground Truth、20 道不可回答题和类别定义。

生成器只写入指定输出目录，不导入数据库，也不调用 ModelRAG 服务。
