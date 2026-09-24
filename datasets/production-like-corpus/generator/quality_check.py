#!/usr/bin/env python3
"""Automated v1.2 structural and semantic quality gates for the corpus."""

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


def extract_section_texts(path: Path, fmt: str, document_id: str, sections: list[str]) -> dict[str, str]:
    result = {f"{document_id}/{section}": "" for section in sections}
    if fmt == "PDF":
        for page in PdfReader(str(path)).pages:
            text = page.extract_text() or ""
            section = next((item for item in sections if re.search(rf"(?m)^{re.escape(item)}\s*$", text)), None)
            if section:
                result[f"{document_id}/{section}"] += "\n" + text
    elif fmt == "DOCX":
        current = None
        for paragraph in Document(path).paragraphs:
            if paragraph.style.name.startswith("Heading 1") and paragraph.text in sections:
                current = paragraph.text
            elif current:
                result[f"{document_id}/{current}"] += "\n" + paragraph.text
    elif fmt == "XLSX":
        workbook = load_workbook(path, data_only=False, read_only=False)
        for worksheet in workbook.worksheets:
            if worksheet.title not in sections:
                continue
            values = [str(cell.value) for row in worksheet.iter_rows() for cell in row if cell.value is not None]
            result[f"{document_id}/{worksheet.title}"] = "\n".join(values)
    elif fmt == "MD":
        current = None
        for line in path.read_text(encoding="utf-8").splitlines():
            match = re.match(r"^## (?!#)(.+)$", line)
            if match and match.group(1) in sections:
                current = match.group(1)
            elif current:
                result[f"{document_id}/{current}"] += "\n" + line
    return result


def faq_entries(text: str) -> list[dict]:
    entries = []
    topic = None
    lines = text.splitlines()
    for index, line in enumerate(lines):
        topic_match = re.match(r"^## (?!#)(.+)$", line)
        if topic_match:
            topic = topic_match.group(1)
            continue
        question_match = re.match(r"^### Q\d{3} (.+)$", line)
        if not question_match or topic is None:
            continue
        answer = next((candidate.strip() for candidate in lines[index + 1:] if candidate.strip()), "")
        entries.append({"topic": topic, "question": question_match.group(1), "answer": answer})
    return entries


def duplicate_ratio(values: list[str]) -> float:
    normalized = [re.sub(r"\s+", "", value) for value in values if value.strip()]
    return 0.0 if not normalized else (len(normalized) - len(set(normalized))) / len(normalized)


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
    version_valid = all(q.get("versionEvidence", {}).get("currentAuthority") and
                        "historicalEvidence" in q.get("versionEvidence", {}) and
                        "forbiddenAsAuthority" in q.get("versionEvidence", {})
                        for q in version_questions)
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
    section_texts: dict[str, str] = {}
    file_details: dict[str, dict] = {}
    open_errors = []
    actual_files = []
    for doc in documents:
        path = root / doc["filename"]
        actual_files.append(path)
        try:
            text, detail = extract_text(path, doc["format"])
            extracted[doc["documentId"]] = text
            spec = next(item for item in DOCUMENTS if item.document_id == doc["documentId"])
            section_texts.update(extract_section_texts(path, doc["format"], doc["documentId"], list(spec.sections)))
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
        if not any(fact.get("sourceText", fact["statement"]) in extracted.get(source, "")
                   for source in fact["primarySources"]):
            fact_presence_missing.append(fact["factId"])
    gate("fact_statements_present_in_sources", not fact_presence_missing, fact_presence_missing or "all present")

    facts_by_id = {fact["factId"]: fact for fact in fact_values}
    location_failures = []
    for fact in fact_values:
        locations = fact.get("sourceLocations", [])
        if {item.get("documentId") for item in locations} != set(fact["primarySources"]):
            location_failures.append({"factId": fact["factId"], "reason": "source document mismatch"})
        for location in locations:
            path = location.get("sectionPath", "")
            source_text = location.get("sourceText", fact.get("sourceText", fact["statement"]))
            if path not in section_texts:
                location_failures.append({"factId": fact["factId"], "sectionPath": path,
                                          "reason": "section missing"})
            elif source_text not in section_texts[path]:
                location_failures.append({"factId": fact["factId"], "sectionPath": path,
                                          "reason": "fact not present in section"})
    gate("factSectionAlignment", not location_failures,
         {"alignedFacts": len(fact_values) if not location_failures else len(fact_values) - len({x['factId'] for x in location_failures}),
          "failures": location_failures})

    section_evidence_failures = []
    for question in questions:
        expected_rows = [
            {"factId": fact_id, "statement": facts_by_id[fact_id]["statement"], **location}
            for fact_id in question["factIds"]
            for location in facts_by_id[fact_id]["sourceLocations"]
        ]
        expected_paths = list(dict.fromkeys(row["sectionPath"] for row in expected_rows))
        if question.get("expectedSections") != expected_paths or question.get("requiredFactLocations") != expected_rows:
            section_evidence_failures.append({"questionId": question["id"], "reason": "derived locations differ"})
            continue
        for row in expected_rows:
            if row["sourceText"] not in section_texts.get(row["sectionPath"], ""):
                section_evidence_failures.append({"questionId": question["id"], "factId": row["factId"],
                                                  "sectionPath": row["sectionPath"]})
    gate("expectedSectionContainsRequiredFact", not section_evidence_failures,
         {"questions": len(questions), "failures": section_evidence_failures})

    satisfiability_failures = []
    for row in evidence:
        required = set(row.get("requiredDocumentIds", []))
        forbidden = set(row.get("forbiddenDocumentIds", []))
        if required & forbidden:
            satisfiability_failures.append({"questionId": row["questionId"], "reason": "required document forbidden"})
        expected_paths = list(dict.fromkeys(item["sectionPath"] for item in row.get("requiredFactLocations", [])))
        if row.get("requiredSectionPaths") != expected_paths:
            satisfiability_failures.append({"questionId": row["questionId"], "reason": "required paths differ"})
        if any(item["documentId"] not in required for item in row.get("requiredFactLocations", [])):
            satisfiability_failures.append({"questionId": row["questionId"], "reason": "fact location document not required"})
        if row.get("versionEvidence") and row["retrievalGroundTruth"].get("rejectStaleDocuments"):
            satisfiability_failures.append({"questionId": row["questionId"], "reason": "version evidence rejects history"})
    gate("evidenceSatisfiable", not satisfiability_failures,
         {"questions": len(evidence), "failures": satisfiability_failures})

    authority_failures = []
    for question in version_questions:
        roles = question.get("versionEvidence", {})
        current_ids = {item["factId"] for item in roles.get("currentAuthority", [])}
        historical_ids = {item["factId"] for item in roles.get("historicalEvidence", [])}
        forbidden_ids = {item["factId"] for item in roles.get("forbiddenAsAuthority", [])}
        if not current_ids or current_ids & historical_ids or forbidden_ids != historical_ids:
            authority_failures.append({"questionId": question["id"], "current": sorted(current_ids),
                                       "historical": sorted(historical_ids), "forbidden": sorted(forbidden_ids)})
        if any(facts_by_id[fact_id]["status"] != "CURRENT" for fact_id in current_ids):
            authority_failures.append({"questionId": question["id"], "reason": "non-current fact is authority"})
        evidence_roles = evidence_by_id[question["id"]].get("versionEvidence")
        if evidence_roles != roles or evidence_by_id[question["id"]].get("forbiddenDocumentIds"):
            authority_failures.append({"questionId": question["id"], "reason": "question/evidence role mismatch"})
    gate("versionAuthorityConsistency", not authority_failures,
         {"questions": len(version_questions), "failures": authority_failures})

    faq_details = {}
    faq_mismatches = []
    faq_questions_and_answers = []
    for doc in documents:
        if doc["format"] != "MD":
            continue
        entries = faq_entries(extracted[doc["documentId"]])
        mismatches = [entry for entry in entries
                      if entry["topic"] not in entry["question"] or entry["topic"] not in entry["answer"]]
        banned = [entry for entry in entries
                  if "金额、数据等级、生产权限或紧急处置" in entry["answer"]]
        faq_mismatches.extend({"documentId": doc["documentId"], **entry} for entry in mismatches + banned)
        values = [item for entry in entries for item in (entry["question"], entry["answer"])]
        faq_questions_and_answers.extend(values)
        faq_details[doc["documentId"]] = {"entries": len(entries), "duplicateRatio": duplicate_ratio(values),
                                           "mismatches": len(mismatches) + len(banned)}
    gate("faqQuestionAnswerTopicalConsistency", not faq_mismatches,
         {"documents": faq_details, "failures": faq_mismatches})

    prose_units = []
    for doc_id, text in extracted.items():
        if next(doc for doc in documents if doc["documentId"] == doc_id)["format"] == "MD":
            continue
        prose_units.extend(line.strip() for line in text.splitlines()
                           if len(line.strip()) >= 60
                           and not line.strip().startswith(("文档编号：", "规则条款：")))
    prose_duplicate_ratio = duplicate_ratio(prose_units)
    faq_duplicate_ratio = duplicate_ratio(faq_questions_and_answers)
    gate("duplicateTemplateRatio", prose_duplicate_ratio <= 0.08 and faq_duplicate_ratio <= 0.01,
         {"proseRatio": prose_duplicate_ratio, "faqRatio": faq_duplicate_ratio,
          "proseUnits": len(prose_units), "faqUnits": len(faq_questions_and_answers)})

    leakage = []
    for doc_id, text in extracted.items():
        for fact_id in fact_ids:
            if re.search(rf"(?<![A-Z0-9-]){re.escape(fact_id)}(?![A-Z0-9-])", text):
                leakage.append({"documentId": doc_id, "factId": fact_id})
    gate("noInternalFactIdLeakage", not leakage, leakage or "none")

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
