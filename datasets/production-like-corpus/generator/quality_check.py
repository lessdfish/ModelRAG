#!/usr/bin/env python3
"""Automated Spec v1.1 quality gates for the generated corpus."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
import sys
from collections import Counter, defaultdict
from pathlib import Path

from docx import Document
from openpyxl import load_workbook
from pypdf import PdfReader

from corpus_data import CATEGORY_COUNTS, DOCUMENTS, HARD_NEGATIVES, RELATIONS, SPEC_VERSION


PROHIBITED = ("这是测试数据", "为了RAG测试")


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parent.parent)
    parser.add_argument("--write-report", action="store_true")
    return parser.parse_args()


def load_json(path: Path):
    return json.loads(path.read_text(encoding="utf-8"))


def load_jsonl(path: Path) -> list[dict]:
    return [json.loads(line) for line in path.read_text(encoding="utf-8").splitlines() if line.strip()]


def extract_text(path: Path, fmt: str) -> tuple[str, dict]:
    if fmt == "PDF":
        reader = PdfReader(str(path))
        return "\n".join(page.extract_text() or "" for page in reader.pages), {"pages": len(reader.pages)}
    if fmt == "DOCX":
        document = Document(path)
        chunks = [paragraph.text for paragraph in document.paragraphs]
        for table in document.tables:
            for row in table.rows:
                chunks.extend(cell.text for cell in row.cells)
        return "\n".join(chunks), {"paragraphs": len(document.paragraphs), "tables": len(document.tables)}
    if fmt == "XLSX":
        workbook = load_workbook(path, data_only=False, read_only=False)
        chunks = []
        structured = []
        for worksheet in workbook.worksheets:
            nonempty = 0
            for row in worksheet.iter_rows():
                for cell in row:
                    if cell.value is not None:
                        nonempty += 1
                        chunks.append(str(cell.value))
            structured.append({"sheet": worksheet.title, "rows": worksheet.max_row,
                               "columns": worksheet.max_column, "nonempty": nonempty,
                               "tables": len(worksheet.tables)})
        return "\n".join(chunks), {"sheets": workbook.sheetnames, "structured": structured}
    if fmt == "MD":
        value = path.read_text(encoding="utf-8")
        return value, {"qaCount": len(re.findall(r"^### Q\d{3} ", value, re.M))}
    raise ValueError(fmt)


def sha256(path: Path) -> str:
    digest = hashlib.sha256(path.read_bytes()).hexdigest()
    return digest


def run(root: Path) -> dict:
    manifest = load_json(root / "metadata" / "manifest.json")
    registry = load_json(root / "metadata" / "fact_registry.json")
    relations = load_json(root / "metadata" / "document_relations.json")
    graph = load_json(root / "metadata" / "version_graph.json")
    questions = load_jsonl(root / "evaluation" / "questions.jsonl")
    evidence = load_jsonl(root / "evaluation" / "expected_evidence.jsonl")
    unanswerable = load_jsonl(root / "evaluation" / "unanswerable.jsonl")
    categories = load_json(root / "evaluation" / "categories.json")

    gates: dict[str, dict] = {}
    failures: list[str] = []

    def gate(name: str, passed: bool, detail) -> None:
        gates[name] = {"passed": bool(passed), "detail": detail}
        if not passed:
            failures.append(f"{name}: {detail}")

    documents = manifest.get("documents", [])
    document_ids = {doc["documentId"] for doc in documents}
    fact_values = registry.get("facts", [])
    fact_ids = {fact["factId"] for fact in fact_values}
    gate("01_document_count", len(documents) == 30 and len(document_ids) == 30,
         {"documents": len(documents), "uniqueIds": len(document_ids)})
    gate("02_fact_count", len(fact_values) == 100 and len(fact_ids) == 100,
         {"facts": len(fact_values), "uniqueIds": len(fact_ids)})
    gate("03_question_count", len(questions) == 150, len(questions))
    gate("04_unanswerable_count", len(unanswerable) == 20, len(unanswerable))
    gate("05_fact_primary_source", all(fact.get("primarySources") and
         all(source in document_ids for source in fact["primarySources"]) for fact in fact_values),
         "every fact has one or more manifest sources")
    invalid_fact_refs = sorted({fact_id for question in questions for fact_id in question.get("factIds", [])
                                if fact_id not in fact_ids})
    gate("06_question_fact_references", not invalid_fact_refs, invalid_fact_refs or "all valid")
    invalid_document_refs = sorted({doc_id for question in questions for doc_id in question.get("expectedDocuments", [])
                                    if doc_id not in document_ids})
    gate("07_question_document_references", not invalid_document_refs, invalid_document_refs or "all valid")
    version_questions = [q for q in questions if q["category"] == "VERSION_CONFLICT"]
    superseded = {doc["documentId"] for doc in documents if doc["status"] == "SUPERSEDED"}
    version_valid = all(q.get("forbiddenEvidence") or superseded.intersection(
        source for fact_id in q["factIds"] for source in next(
            fact["primarySources"] for fact in fact_values if fact["factId"] == fact_id)) for q in version_questions)
    gate("08_version_conflict_evidence", version_valid, {"questions": len(version_questions)})
    cross = [q for q in questions if q["category"] == "CROSS_DOCUMENT"]
    cross_valid = all(len(q["expectedDocuments"]) >= 2 or len(q["requiredEvidence"]) >= 2 for q in cross)
    evidence_by_id = {row["questionId"]: row for row in evidence}
    cross_valid = cross_valid and all(evidence_by_id[q["id"]]["requiresAllEvidence"] for q in cross)
    gate("09_cross_document_completeness", cross_valid, {"questions": len(cross)})
    formats = {doc["documentId"]: doc["format"] for doc in documents}
    table_questions = [q for q in questions if q["category"] == "TABLE"]
    table_valid = all(any(formats[doc_id] == "XLSX" for doc_id in q["expectedDocuments"]) for q in table_questions)
    gate("10_table_sources", table_valid, {"questions": len(table_questions)})
    bm25 = [q for q in questions if q["category"] == "KEYWORD_BM25"]
    exact_count = sum(bool(re.search(r"(?:[A-Z]{2,}-ERR-\d+|(?:HR|FIN|IT|SEC|PROC|LEG|ENG|OPS)-\d{3}|Break Glass|P0)", q["query"])) for q in bm25)
    gate("11_bm25_exact_identifiers", exact_count >= 10, {"exactIdentifierQuestions": exact_count, "total": len(bm25)})
    long_docs = {doc["documentId"] for doc in documents if doc.get("pageTarget") and doc["pageTarget"] >= 25}
    structure = [q for q in questions if q["category"] == "STRUCTURE_AGENTIC"]
    structure_valid = all(len(q["expectedSections"]) >= 2 and any(doc in long_docs for doc in q["expectedDocuments"])
                          for q in structure)
    gate("12_structure_agentic_sections", structure_valid, {"questions": len(structure)})

    status = {doc["documentId"]: doc["status"] for doc in documents}
    relation_valid = True
    reasons = []
    for relation in relations["relations"]:
        if relation["from"] not in status or relation["to"] not in status or relation["from"] == relation["to"]:
            relation_valid = False
            reasons.append(relation)
        if relation["type"] == "SUPERSEDES" and not (
                status[relation["from"]] == "ACTIVE" and status[relation["to"]] == "SUPERSEDED"):
            relation_valid = False
            reasons.append(relation)
    gate("13_version_relation_consistency", relation_valid and graph.get("specVersion") == SPEC_VERSION,
         reasons or "consistent")

    extracted: dict[str, str] = {}
    file_details: dict[str, dict] = {}
    open_errors = []
    actual_files = []
    for doc in documents:
        path = root / doc["filename"]
        actual_files.append(path)
        try:
            text, detail = extract_text(path, doc["format"])
            extracted[doc["documentId"]] = text
            detail.update({"bytes": path.stat().st_size, "sha256": sha256(path), "characters": len(text)})
            file_details[doc["documentId"]] = detail
            if detail["sha256"] != doc["sha256"]:
                open_errors.append(f"{doc['documentId']} hash differs from manifest")
        except Exception as error:  # noqa: BLE001
            open_errors.append(f"{doc['documentId']}: {type(error).__name__}: {error}")
    corpus_files = [path for path in (root / "corpus").rglob("*") if path.is_file()]
    gate("14_files_open", not open_errors and len(corpus_files) == 30,
         {"opened": len(file_details), "corpusFiles": len(corpus_files), "errors": open_errors})

    xlsx_valid = True
    xlsx_details = {}
    for doc in documents:
        if doc["format"] != "XLSX":
            continue
        detail = file_details.get(doc["documentId"], {})
        sheets = detail.get("sheets", [])
        structures = detail.get("structured", [])
        valid_structures = [item for item in structures if item["rows"] >= 6 and item["columns"] >= 3
                            and item["nonempty"] >= 12 and item["tables"] >= 1]
        passed = len(sheets) >= 2 and len(valid_structures) == len(sheets) and "Sheet" not in sheets
        xlsx_valid &= passed
        xlsx_details[doc["documentId"]] = {"sheets": sheets, "structuredSheets": len(valid_structures), "passed": passed}
    gate("15_xlsx_structured", xlsx_valid, xlsx_details)

    substantive = True
    substantive_details = {}
    for doc in documents:
        if doc["format"] not in {"PDF", "DOCX"}:
            continue
        chars = file_details.get(doc["documentId"], {}).get("characters", 0)
        target_pages = doc.get("pageTarget") or 1
        # A deliberately short 2–4 page notice is substantive at a lower
        # absolute threshold than a policy or handbook, while still needing
        # dense, extractable content on every planned page.
        minimum = max(1500 if target_pages <= 4 else 4000, target_pages * 450)
        passed = chars >= minimum
        substantive &= passed
        substantive_details[doc["documentId"]] = {"characters": chars, "minimum": minimum, "passed": passed}
    gate("16_pdf_docx_substantive", substantive, substantive_details)
    prohibited_hits = []
    for doc_id, text in extracted.items():
        for phrase in PROHIBITED:
            if phrase in text:
                prohibited_hits.append({"documentId": doc_id, "phrase": phrase})
    gate("17_no_immersion_breaking_phrases", not prohibited_hits, prohibited_hits or "none")

    category_counts = Counter(question["category"] for question in questions)
    gate("category_distribution", dict(category_counts) == CATEGORY_COUNTS and categories["counts"] == CATEGORY_COUNTS,
         dict(category_counts))
    gate("evidence_row_alignment", len(evidence) == len(questions) and set(evidence_by_id) == {q["id"] for q in questions},
         {"questions": len(questions), "evidenceRows": len(evidence)})
    gate("unanswerable_behavior", all(row.get("expectedBehavior") == "INSUFFICIENT_EVIDENCE" for row in unanswerable),
         {"rows": len(unanswerable)})

    fact_presence_missing = []
    for fact in fact_values:
        if not any(fact["statement"] in extracted.get(source, "") for source in fact["primarySources"]):
            fact_presence_missing.append(fact["factId"])
    gate("fact_statements_present_in_sources", not fact_presence_missing, fact_presence_missing or "all present")

    total_characters = sum(detail.get("characters", 0) for detail in file_details.values())
    gate("content_scale", 800_000 <= total_characters <= 1_500_000, total_characters)

    faq_ranges = {"HR-005": (300, 400), "FIN-005": (190, 210), "SEC-005": (150, 200),
                  "LEG-002": (150, 200), "OPS-004": (240, 260)}
    faq_details = {}
    faq_valid = True
    for doc_id, (minimum, maximum) in faq_ranges.items():
        count = file_details.get(doc_id, {}).get("qaCount", 0)
        passed = minimum <= count <= maximum
        faq_valid &= passed
        faq_details[doc_id] = {"count": count, "range": [minimum, maximum], "passed": passed}
    gate("faq_scale", faq_valid, faq_details)

    pdf_pages = {}
    pdf_valid = True
    targets = {doc.document_id: doc.page_target for doc in DOCUMENTS}
    for doc in documents:
        if doc["format"] != "PDF":
            continue
        pages = file_details.get(doc["documentId"], {}).get("pages", 0)
        target = targets[doc["documentId"]]
        passed = pages == target
        pdf_valid &= passed
        pdf_pages[doc["documentId"]] = {"pages": pages, "target": target, "passed": passed}
    gate("pdf_page_targets", pdf_valid, pdf_pages)

    hard_negative_terms = {
        "permissions": ["OA权限", "GitLab权限", "普通数据库权限", "生产数据库只读权限", "生产数据库写权限", "采购审批权限"],
        "lodging": ["普通差旅住宿", "长期派驻住宿补贴", "驻场补助", "海外住宿", "会议临时住宿标准"],
        "leave": ["产假", "陪产假", "育儿假", "病假", "婚假", "事假"],
        "incident": ["P0", "P1", "数据库性能下降", "核心业务不可用", "安全事件", "普通故障"],
        "procurement_contract": ["采购审批", "合同签署权限", "供应商准入", "付款审批", "单一来源例外"],
    }
    all_text = "\n".join(extracted.values())
    hn_detail = {group: {term: all_text.count(term) for term in terms} for group, terms in hard_negative_terms.items()}
    hn_valid = all(all(count > 0 for count in counts.values()) for counts in hn_detail.values())
    gate("hard_negative_coverage", hn_valid, hn_detail)

    report = {
        "specVersion": SPEC_VERSION,
        "status": "PASSED" if not failures else "FAILED",
        "documentCount": len(documents),
        "formatCounts": dict(Counter(doc["format"] for doc in documents)),
        "totalCharacters": total_characters,
        "factCount": len(fact_values),
        "questionCount": len(questions),
        "categoryCounts": dict(category_counts),
        "unanswerableCount": len(unanswerable),
        "files": file_details,
        "gates": gates,
        "failures": failures,
    }
    return report


def main() -> int:
    options = arguments()
    report = run(options.root.resolve())
    if options.write_report:
        target = options.root.resolve() / "metadata" / "quality_report.json"
        target.write_text(json.dumps(report, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")
    print(json.dumps({"status": report["status"], "documents": report["documentCount"],
                      "characters": report["totalCharacters"], "facts": report["factCount"],
                      "questions": report["questionCount"], "unanswerable": report["unanswerableCount"],
                      "failures": report["failures"]}, ensure_ascii=False))
    return 0 if report["status"] == "PASSED" else 1


if __name__ == "__main__":
    raise SystemExit(main())
