#!/usr/bin/env python3
"""Generate the ModelRAG Production-like Evaluation Corpus v1.1 deterministically."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import random
import re
import shutil
import sys
import zipfile
from datetime import datetime, timezone
from pathlib import Path

from docx import Document
from docx.enum.section import WD_SECTION
from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.oxml import OxmlElement
from docx.oxml.ns import qn
from docx.shared import Cm, Pt, RGBColor
from openpyxl import Workbook
from openpyxl.styles import Alignment, Border, Font, PatternFill, Side
from openpyxl.utils import get_column_letter
from openpyxl.worksheet.table import Table, TableStyleInfo
from pypdf import PdfReader
from reportlab import rl_config
from reportlab.lib.colors import HexColor
from reportlab.lib.enums import TA_CENTER, TA_JUSTIFY, TA_LEFT
from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.pdfgen import canvas
from reportlab.platypus import Paragraph

from corpus_data import (CATEGORY_COUNTS, COMPANY, DOCUMENTS, HARD_NEGATIVES, RELATIONS,
                         SEED, SPEC_VERSION, UNANSWERABLE, DocumentSpec)
from evaluation_data import QUESTION_SETS, VERSION_FORBIDDEN


GENERATOR_DIR = Path(__file__).resolve().parent
CORPUS_ROOT = GENERATOR_DIR.parent
REPO_ROOT = CORPUS_ROOT.parents[1]
DEFAULT_SPEC = REPO_ROOT / "docs" / "evaluation" / "ModelRAG-Production-Like-Corpus-Spec-v1.1.md"
FIXED_TIME = datetime(2026, 9, 17, 0, 0, 0, tzinfo=timezone.utc)
ZIP_TIME = (2026, 9, 17, 0, 0, 0)
FORMATS = {"PDF": ".pdf", "DOCX": ".docx", "XLSX": ".xlsx", "MD": ".md"}


DOMAIN_PROFILES = {
    "HR": {
        "roles": ["员工", "直属负责人", "HRBP", "人力资源共享服务中心", "薪酬福利团队"],
        "objects": ["入职资料", "考勤记录", "休假申请", "薪酬数据", "福利凭证", "离职交接单"],
        "systems": ["员工自助平台", "考勤系统", "审批中心", "人事档案库"],
        "risks": ["口径不一致", "材料缺失", "审批倒置", "超期补录", "个人信息扩散"],
    },
    "FINANCE": {
        "roles": ["出差人", "费用审核人", "部门负责人", "财务共享中心", "预算负责人"],
        "objects": ["出差申请", "交通订单", "住宿账单", "发票", "公司卡流水", "报销单"],
        "systems": ["差旅平台", "费用系统", "电子票夹", "预算控制台"],
        "risks": ["超标准支出", "重复报销", "费用归属错误", "凭证不完整", "事后审批"],
    },
    "IT": {
        "roles": ["员工", "服务台工程师", "系统管理员", "项目Owner", "网络值班工程师"],
        "objects": ["账号", "设备证书", "MFA令牌", "客户端日志", "权限工单", "终端基线"],
        "systems": ["服务台", "SSO平台", "VPN网关", "终端管理平台", "GitLab"],
        "risks": ["凭据失效", "配置漂移", "权限残留", "证书过期", "错误码误判"],
    },
    "SECURITY": {
        "roles": ["数据所有者", "System Owner", "DBA", "SOC分析员", "Security On-call"],
        "objects": ["数据资产", "访问申请", "审计日志", "证据副本", "加密密钥", "事件记录"],
        "systems": ["数据目录", "访问控制平台", "SOC工单", "审计平台"],
        "risks": ["越权访问", "非授权披露", "证据污染", "权限超期", "敏感数据扩散"],
    },
    "PROCUREMENT": {
        "roles": ["采购申请人", "部门负责人", "采购经理", "预算负责人", "供应商管理员"],
        "objects": ["采购申请", "报价单", "比价记录", "准入材料", "验收单", "付款资料"],
        "systems": ["采购平台", "供应商门户", "预算系统", "合同台账"],
        "risks": ["拆分采购", "无预算采购", "准入缺失", "单一来源滥用", "验收证据不足"],
    },
    "LEGAL": {
        "roles": ["业务经办人", "合同管理员", "法务审核人", "授权签署人", "印章管理员"],
        "objects": ["合同模板", "非标条款", "审核意见", "签署文本", "归档清单", "终止通知"],
        "systems": ["合同管理平台", "电子签署平台", "印章系统", "档案库"],
        "risks": ["未审先签", "文本版本错误", "越权签署", "用印材料缺失", "补签倒置"],
    },
    "ENGINEERING": {
        "roles": ["产品负责人", "技术负责人", "开发工程师", "测试负责人", "发布经理"],
        "objects": ["需求说明", "设计评审记录", "代码变更", "测试报告", "发布单", "验收结论"],
        "systems": ["需求平台", "代码平台", "持续集成平台", "发布平台"],
        "risks": ["需求漂移", "设计遗漏", "测试不充分", "未批先发", "验收缺失"],
    },
    "OPERATIONS": {
        "roles": ["变更执行人", "Incident Commander", "DBA On-call", "Network On-call", "Business Owner"],
        "objects": ["变更单", "风险评估", "灰度计划", "回滚方案", "事故时间线", "复盘记录"],
        "systems": ["变更平台", "监控平台", "事件协同频道", "值班系统"],
        "risks": ["窗口冲突", "回滚失败", "告警遗漏", "沟通失序", "恢复未验证"],
    },
}

BOUNDARY_PARAGRAPHS = {
    "HR": "办理住宿或补贴时，应明确区分普通差旅住宿、长期派驻住宿补贴、驻场补助、海外住宿和会议临时住宿标准；名称相近不代表预算来源、适用期间和审批链相同。",
    "FINANCE": "费用审核应区分普通差旅住宿、长期派驻住宿补贴、驻场补助、海外住宿和会议临时住宿标准，分别核对出行性质、适用期限、费用来源和例外批准。",
    "IT": "权限工单必须区分OA权限、GitLab权限、普通数据库权限、生产数据库只读权限、生产数据库写权限和采购审批权限；一个流程的批准不得替代另一个控制域。",
    "SECURITY": "访问审查必须区分OA权限、GitLab权限、普通数据库权限、生产数据库只读权限、生产数据库写权限和采购审批权限，尤其不得用普通系统授权替代敏感生产访问审批。",
    "PROCUREMENT": "经办人应分别识别采购审批、合同签署权限、供应商准入、付款审批和单一来源例外，前一环节完成不代表后续环节自动通过。",
    "LEGAL": "合同流程与采购审批、合同签署权限、供应商准入、付款审批和单一来源例外相互关联但彼此独立，审核记录应说明各控制点的完成状态。",
    "ENGINEERING": "发布影响分析应区分P0、P1、数据库性能下降、核心业务不可用、安全事件和普通故障，依据真实业务影响选择响应路径。",
    "OPERATIONS": "值班人员应区分P0、P1、数据库性能下降、核心业务不可用、安全事件和普通故障，不得只凭告警名称提升或降低事故等级。",
}


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output-root", type=Path, default=CORPUS_ROOT)
    parser.add_argument("--spec", type=Path, default=DEFAULT_SPEC)
    parser.add_argument("--seed", type=int, default=SEED)
    parser.add_argument("--clean-generated", action="store_true")
    return parser.parse_args()


def stable_rng(*parts: object) -> random.Random:
    digest = hashlib.sha256(":".join(map(str, (SEED, *parts))).encode("utf-8")).digest()
    return random.Random(int.from_bytes(digest[:8], "big"))


def normalize_sources(value: str) -> list[str]:
    result: list[str] = []
    prefix = ""
    for raw in value.split("/"):
        token = raw.strip()
        if re.fullmatch(r"[A-Z]+-\d{3}", token):
            prefix = token.split("-", 1)[0]
            result.append(token)
        elif re.fullmatch(r"\d{3}", token) and prefix:
            result.append(f"{prefix}-{token}")
        elif token:
            raise ValueError(f"Unparseable source token: {token!r} in {value!r}")
    return result


def parse_fact_registry(spec_path: Path) -> list[dict]:
    text = spec_path.read_text(encoding="utf-8")
    pattern = re.compile(r"^\|\s*([A-Z]+-\d{3})\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|\s*(.*?)\s*\|$", re.M)
    facts = []
    seen = set()
    for fact_id, fact, source, retrieval in pattern.findall(text):
        if fact_id in seen:
            continue
        seen.add(fact_id)
        sources = normalize_sources(source)
        facts.append({
            "factId": fact_id,
            "statement": fact.strip(),
            "primarySources": sources,
            "retrievalIntent": retrieval.strip(),
            "status": "HISTORICAL" if retrieval.strip() == "Stale" else "CURRENT",
        })
    if len(facts) != 100:
        raise ValueError(f"Spec v1.1 fact table count is {len(facts)}, expected 100")
    return facts


def filename_for(doc: DocumentSpec) -> str:
    safe = re.sub(r"[\\/:*?\"<>|]", "_", doc.title).replace(" ", "_")
    return f"{doc.document_id}_{safe}{FORMATS[doc.fmt]}"


def document_code(doc: DocumentSpec) -> str:
    kind = {"PDF": "POL", "DOCX": "STD", "XLSX": "MAT", "MD": "FAQ"}[doc.fmt]
    year = doc.effective_date[:4]
    return f"NT-{doc.department[:4]}-{kind}-{year}-{doc.document_id.split('-')[1]}"


def facts_by_document(facts: list[dict]) -> dict[str, list[dict]]:
    result = {doc.document_id: [] for doc in DOCUMENTS}
    for fact in facts:
        for source in fact["primarySources"]:
            if source in result:
                result[source].append(fact)
    return result


def references_for(document_id: str) -> list[str]:
    return sorted({target for source, relation, target, _ in RELATIONS
                   if source == document_id and relation in {"REFERENCES", "RELATED", "OVERRIDES"}})


def supersedes_for(document_id: str) -> list[str]:
    return sorted({target for source, relation, target, _ in RELATIONS
                   if source == document_id and relation == "SUPERSEDES"})


def paragraph_for(doc: DocumentSpec, section: str, page: int, slot: int) -> str:
    rng = stable_rng(doc.document_id, section, page, slot)
    profile = DOMAIN_PROFILES[doc.department]
    role = rng.choice(profile["roles"])
    reviewer = rng.choice([value for value in profile["roles"] if value != role])
    obj = rng.choice(profile["objects"])
    system = rng.choice(profile["systems"])
    risk = rng.choice(profile["risks"])
    record = rng.choice(["审批记录", "操作日志", "交接清单", "复核意见", "时间线", "留痕附件"])
    mode = (page + slot) % 5
    if mode == 4 and page % 5 == 2:
        return BOUNDARY_PARAGRAPHS[doc.department]
    if doc.department in {"IT", "OPERATIONS"}:
        symptoms = ["登录失败且重试无效", "监控指标持续偏离基线", "权限校验结果与预期不一致", "任务执行后状态未收敛", "日志出现连续告警"]
        causes = ["配置未同步", "依赖服务异常", "证书或凭据失效", "变更影响未完全隔离", "网络路径发生漂移"]
        checks = ["确认影响范围和开始时间", "核对最近变更与依赖状态", "保留原始日志并校验时间戳", "使用只读手段复核关键配置", "对照基线执行最小化验证"]
        actions = ["先隔离影响再恢复单一节点", "按操作顺序逐项验证并记录结果", "在受控窗口执行修复", "恢复前准备可执行的回滚步骤", "由值班角色确认服务健康度"]
        manual_paragraphs = [
            (
                f"【适用场景】“{section}”用于处理{obj}相关的标准请求和异常。典型入口是{rng.choice(symptoms)}，但该现象也可能由"
                f"{rng.choice(causes)}引起；因此{role}必须先界定用户、环境、开始时间与影响面，再选择操作路径。测试、预发布与生产环境的"
                f"记录不得混用，未确认对象身份时不得执行写操作。"
            ),
            (
                f"【前置检查】开始前由{role}{rng.choice(checks)}，并在{system}核对{obj}的当前状态、最近变更和依赖健康度。"
                f"{reviewer}负责确认权限与窗口是否有效。遇到信息缺失时先补充时间戳、请求编号和复现条件，不以截图代替原始日志，"
                f"也不得为赶进度跳过只读检查。"
            ),
            (
                f"【操作步骤】第一步保全{record}并建立基线；第二步{rng.choice(actions)}；第三步以业务探针、监控指标和用户侧结果交叉验证。"
                f"每一步都在{system}记录命令或动作、执行人、开始与结束时间。若结果与预期不一致，只允许回到上一已验证状态，"
                f"禁止并行尝试多个不可逆方案。"
            ),
            (
                f"【异常与升级】出现{risk}、连续两次验证失败或影响扩散时，{role}立即停止重复操作并通知{reviewer}。"
                f"升级信息至少包括{obj}、影响范围、已做检查、当前假设和可执行回滚点；紧急处置可先隔离故障面，但事后必须补齐审批和{record}。"
                f"无法在本节边界内恢复时，转入事故响应流程而不是继续试错。"
            ),
            (
                f"【收尾与附件】恢复后由{reviewer}复核监控、访问控制和业务结果，{role}在{system}关闭临时权限或绕行配置。"
                f"工单附件应包含脱敏日志、验证截图、配置差异、回滚结果和时间线；附件名使用请求编号与日期，不上传口令、令牌或完整客户数据。"
                f"值班交接只记录未决事项，已完成步骤不得作为后续轮次的默认假设。"
            ),
        ]
        manual_notes = [
            f"边界确认完成后，在工单摘要中注明本次不处理的相邻问题，避免后续人员把{section}与其他场景合并判断。",
            f"检查结论应写成可复核的事实，而不是“正常”“已看过”等模糊描述；取证时间与{system}时钟存在偏差时一并备注。",
            f"为降低操作扰动，验证样本应从小到大扩展，并在每个阶段设置停止条件；未达到停止条件前不进入下一阶段。",
            f"升级不等同于移交责任，原经办人仍需保持信息连续，直到接手人明确复述现状、风险和下一验证动作。",
            f"归档前删除本地临时副本，确认附件可由授权角色重放验证；仅用于讨论的草稿和过期截图标记为非证据材料。",
        ]
        return manual_paragraphs[mode] + manual_notes[mode]
    obligations = ["在办理前确认适用范围和有效版本", "按照职责分工提交完整材料", "在批准后方可进入下一处理环节", "核对预算、权限与业务依据", "对例外原因进行书面说明"]
    controls = ["双人复核", "分级审批", "到期复查", "凭证校验", "系统留痕", "抽样稽核"]
    exceptions = ["紧急情况", "跨部门协同", "系统短时不可用", "境外或异地场景", "历史资料迁移"]
    formal_paragraphs = [
        (
            f"“{section}”适用于围绕{obj}发生的申请、审核、执行和复核活动。本节所称有效记录，是指能够说明责任人、业务目的、"
            f"适用期间、金额或权限范围以及最终决定的材料。{role}应先确认对象和有效版本；口头沟通、聊天截图或历史表单均不能单独构成办理依据。"
        ),
        (
            f"职责分工上，{role}负责{rng.choice(obligations)}并保证材料真实完整，{reviewer}通过{system}实施{rng.choice(controls)}。"
            f"申请、批准、执行与复核应由相互独立的角色承担；同一人员临时代办时，必须在{record}中写明授权来源、期限与后续复核人，"
            f"避免因{risk}形成无人负责的控制空档。"
        ),
        (
            f"标准流程依次为受理、完整性检查、业务判断、授权决定、执行和归档。{role}提交{obj}后，{reviewer}先核对范围与前置条件，"
            f"再在{system}记录结论；未通过的事项应一次性列明缺口。批准仅对载明的对象和期限有效，范围变化、金额跨档或关键条件变化时应重新进入审批。"
        ),
        (
            f"遇到{rng.choice(exceptions)}，可使用本文件明确的例外路径，但不得据此降低实质控制。经办人应写明不能走标准流程的原因、"
            f"风险缓解措施和恢复标准，由{reviewer}限定期限并复核。涉及{risk}或职责冲突的事项不得口头追认；紧急办理结束后应补齐{record}并登记复盘结论。"
        ),
        (
            f"引用其他制度时，应同时记录文件编号、版本和生效日期；存在新旧口径差异的，以明确的替代、覆盖关系及事项发生时间判定。"
            f"归档附件包括{obj}原件、审批链、{record}、必要的核验截图和例外说明，并按{system}的保留规则存放。抽查样本中的无关批注、"
            f"旧模板页眉和测试数据属于背景噪声，不得被当作正式规则或新的授权依据。"
        ),
    ]
    formal_notes = [
        f"如事项同时受多个制度约束，应先确定主责文件，再把其他文件作为补充条件列入办理清单，不能择一规避。",
        f"岗位变动、代理审批或跨组织协作不改变原职责边界；临时授权到期后，{system}应恢复常规责任链。",
        f"流程中的等待、退回和补正也属于办理过程，系统记录应保留原时间顺序，不得通过重新发起掩盖曾经的缺口。",
        f"例外决定只对本次载明事项有效，不自动形成惯例；同类例外反复出现时，应由归口部门评估是否修订制度。",
        f"附件索引应能从正文条款追溯到具体材料，复印件或导出件需标注来源；与结论无关的个人信息应按最小必要原则遮蔽。",
    ]
    return formal_paragraphs[mode] + formal_notes[mode]


def fact_paragraph(fact: dict) -> str:
    return f"规则条款：{fact['statement']}。该条款的适用对象、条件和证据应结合本章上下文判断，不得脱离有效版本单独引用。"


def identity_lines(doc: DocumentSpec) -> list[tuple[str, str]]:
    return [
        ("文档编号", document_code(doc)),
        ("文档ID", doc.document_id),
        ("版本", doc.version),
        ("状态", "生效" if doc.status == "ACTIVE" else "已废止"),
        ("密级", "内部"),
        ("归口部门", doc.department),
        ("发布日", doc.publish_date),
        ("生效日", doc.effective_date),
        ("替代文件", "、".join(supersedes_for(doc.document_id)) or "无"),
        ("引用文件", "、".join(references_for(doc.document_id)) or "无"),
    ]


def register_pdf_fonts() -> tuple[str, str]:
    body = "FangSong"
    heading = "SimHei"
    if body not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(body, r"C:\Windows\Fonts\simfang.ttf"))
    if heading not in pdfmetrics.getRegisteredFontNames():
        pdfmetrics.registerFont(TTFont(heading, r"C:\Windows\Fonts\simhei.ttf"))
    return body, heading


def draw_pdf_paragraph(c: canvas.Canvas, text: str, x: float, y: float, width: float,
                       font: str, size: float = 10.2, leading: float = 16.5,
                       alignment: int = TA_JUSTIFY) -> float:
    style = ParagraphStyle("body", fontName=font, fontSize=size, leading=leading,
                           textColor=HexColor("#222222"), alignment=alignment,
                           wordWrap="CJK", spaceAfter=4)
    p = Paragraph(html.escape(text), style)
    _, height = p.wrap(width, 180 * mm)
    p.drawOn(c, x, y - height)
    return y - height - 4 * mm


def write_pdf(doc: DocumentSpec, output: Path, facts: list[dict]) -> None:
    rl_config.invariant = 1
    body_font, heading_font = register_pdf_fonts()
    c = canvas.Canvas(str(output), pagesize=A4, invariant=1, pageCompression=1)
    c.setTitle(doc.title)
    c.setAuthor(COMPANY)
    width, height = A4
    pages = doc.page_target or 1
    fact_index = 0
    for page in range(1, pages + 1):
        c.setFillColor(HexColor("#000000"))
        if page == 1:
            c.setFont(heading_font, 22)
            c.drawCentredString(width / 2, height - 42 * mm, doc.title)
            c.setFont(body_font, 11)
            c.drawCentredString(width / 2, height - 54 * mm, f"{COMPANY}  {doc.version}")
            y = height - 74 * mm
            for label, value in identity_lines(doc):
                c.setFont(heading_font, 9.5)
                c.drawString(35 * mm, y, f"{label}：")
                c.setFont(body_font, 9.5)
                c.drawString(68 * mm, y, value)
                y -= 8 * mm
            y -= 4 * mm
            intro = f"本文件由{doc.department}归口管理，适用于{COMPANY}相关员工与业务活动。使用人应先确认版本、适用范围和生效日期，再按章节定位职责、流程、例外和记录要求。"
            draw_pdf_paragraph(c, intro, 25 * mm, y, width - 50 * mm, body_font)
        else:
            section = doc.sections[(page - 2) % len(doc.sections)]
            c.setFont(heading_font, 15)
            c.drawString(24 * mm, height - 30 * mm, f"{section}")
            c.setFont(body_font, 9)
            c.setFillColor(HexColor("#666666"))
            c.drawRightString(width - 24 * mm, height - 30 * mm, f"{page}.{(page - 2) % len(doc.sections) + 1}")
            c.setFillColor(HexColor("#222222"))
            y = height - 42 * mm
            if fact_index < len(facts):
                y = draw_pdf_paragraph(c, fact_paragraph(facts[fact_index]), 24 * mm, y,
                                       width - 48 * mm, body_font, 10.4, 17)
                fact_index += 1
            for slot in range(5):
                y = draw_pdf_paragraph(c, paragraph_for(doc, section, page, slot), 24 * mm, y,
                                       width - 48 * mm, body_font)
            if page % 7 == 0:
                y = draw_pdf_paragraph(c,
                    f"记录要求：本页涉及的申请、审批、执行和复核信息应以{document_code(doc)}为制度依据，保存责任角色、时间、范围与结论，便于后续抽查和争议处理。",
                    24 * mm, y, width - 48 * mm, body_font, 9.7, 15.5)
        c.setStrokeColor(HexColor("#C7CDD4"))
        c.line(24 * mm, 18 * mm, width - 24 * mm, 18 * mm)
        c.setFont(body_font, 8)
        c.setFillColor(HexColor("#666666"))
        c.drawString(24 * mm, 11 * mm, f"{document_code(doc)}  内部")
        c.drawRightString(width - 24 * mm, 11 * mm, f"第 {page} 页 / 共 {pages} 页")
        c.showPage()
    c.save()
    actual = len(PdfReader(str(output)).pages)
    if actual != pages:
        raise RuntimeError(f"PDF page mismatch for {doc.document_id}: {actual} != {pages}")


def set_cell_shading(cell, fill: str) -> None:
    tc_pr = cell._tc.get_or_add_tcPr()
    shd = tc_pr.find(qn("w:shd"))
    if shd is None:
        shd = OxmlElement("w:shd")
        tc_pr.append(shd)
    shd.set(qn("w:fill"), fill)


def set_cell_margins(cell, top=100, start=120, bottom=100, end=120) -> None:
    tc = cell._tc
    tc_pr = tc.get_or_add_tcPr()
    tc_mar = tc_pr.first_child_found_in("w:tcMar")
    if tc_mar is None:
        tc_mar = OxmlElement("w:tcMar")
        tc_pr.append(tc_mar)
    for name, value in (("top", top), ("start", start), ("bottom", bottom), ("end", end)):
        node = tc_mar.find(qn(f"w:{name}"))
        if node is None:
            node = OxmlElement(f"w:{name}")
            tc_mar.append(node)
        node.set(qn("w:w"), str(value))
        node.set(qn("w:type"), "dxa")


def style_docx(document: Document) -> None:
    styles = document.styles
    for name, size, bold in (("Normal", 9.5, False), ("Title", 23, True),
                             ("Heading 1", 15, True), ("Heading 2", 12, True)):
        style = styles[name]
        style.font.name = "Microsoft YaHei"
        style._element.rPr.rFonts.set(qn("w:eastAsia"), "Microsoft YaHei")
        style.font.size = Pt(size)
        style.font.bold = bold
        style.font.color.rgb = RGBColor(0, 0, 0)
    normal = styles["Normal"].paragraph_format
    normal.line_spacing = 1.15
    normal.space_after = Pt(3)
    for section in document.sections:
        section.top_margin = Cm(2.2)
        section.bottom_margin = Cm(2.0)
        section.left_margin = Cm(2.4)
        section.right_margin = Cm(2.4)


def write_docx(doc: DocumentSpec, output: Path, facts: list[dict]) -> None:
    document = Document()
    style_docx(document)
    props = document.core_properties
    props.title = doc.title
    props.subject = f"{COMPANY} 内部制度"
    props.author = COMPANY
    props.last_modified_by = COMPANY
    props.created = FIXED_TIME.replace(tzinfo=None)
    props.modified = FIXED_TIME.replace(tzinfo=None)
    section = document.sections[0]
    header = section.header.paragraphs[0]
    header.text = f"{document_code(doc)}  {doc.title}"
    header.alignment = WD_ALIGN_PARAGRAPH.RIGHT
    for run in header.runs:
        run.font.name = "Microsoft YaHei"
        run.font.size = Pt(8)
        run.font.color.rgb = RGBColor(90, 90, 90)
    footer = section.footer.paragraphs[0]
    footer.text = f"{COMPANY}  内部  {doc.version}"
    footer.alignment = WD_ALIGN_PARAGRAPH.CENTER
    for run in footer.runs:
        run.font.name = "Microsoft YaHei"
        run.font.size = Pt(8)
        run.font.color.rgb = RGBColor(90, 90, 90)

    title = document.add_paragraph(doc.title, style="Title")
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    subtitle = document.add_paragraph(f"{COMPANY}  {doc.version}")
    subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
    table = document.add_table(rows=0, cols=2)
    table.autofit = False
    table.columns[0].width = Cm(4.0)
    table.columns[1].width = Cm(11.5)
    for index, (label, value) in enumerate(identity_lines(doc)):
        cells = table.add_row().cells
        cells[0].text = label
        cells[1].text = value
        for cell in cells:
            cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
            set_cell_margins(cell)
            for p in cell.paragraphs:
                p.paragraph_format.space_after = Pt(1)
                for run in p.runs:
                    run.font.name = "Microsoft YaHei"
                    run.font.size = Pt(9)
        set_cell_shading(cells[0], "D9E2F3" if index % 2 == 0 else "EAF0F8")
    document.add_paragraph(
        f"本文件用于明确{doc.title}的适用边界、职责、流程、例外和记录要求。阅读时应先确认生效日期与引用文件，历史版本只用于追溯，不得覆盖当前有效规则。"
    )

    pages = doc.page_target or 1
    fact_index = 0
    for page in range(2, pages + 1):
        document.add_page_break()
        section_name = doc.sections[(page - 2) % len(doc.sections)]
        document.add_heading(section_name, level=1)
        lead = document.add_paragraph(f"第 {page - 1} 节控制说明")
        lead.runs[0].bold = True
        fact_slots = max(1, (len(facts) + max(1, pages - 2)) // max(1, pages - 1))
        for _ in range(fact_slots):
            if fact_index >= len(facts):
                break
            p = document.add_paragraph(fact_paragraph(facts[fact_index]))
            p.runs[0].bold = True
            fact_index += 1
        # Four distinct semantic paragraphs fit on one planned Word page and,
        # with the rotating mode, cover scope, duties, process, exceptions and
        # evidence/attachments across adjacent sections.
        for slot in range(4):
            document.add_paragraph(paragraph_for(doc, section_name, page, slot))
        if page % 8 == 0:
            document.add_heading("本节记录清单", level=2)
            mini = document.add_table(rows=1, cols=3)
            headers = ("记录", "责任角色", "复核要点")
            for i, value in enumerate(headers):
                mini.cell(0, i).text = value
                set_cell_shading(mini.cell(0, i), "203864")
                for run in mini.cell(0, i).paragraphs[0].runs:
                    run.font.color.rgb = RGBColor(255, 255, 255)
                    run.font.bold = True
            profile = DOMAIN_PROFILES[doc.department]
            for row_index in range(3):
                row = mini.add_row().cells
                row[0].text = profile["objects"][row_index % len(profile["objects"])]
                row[1].text = profile["roles"][row_index % len(profile["roles"])]
                row[2].text = f"确认范围、时间和结论，防止{profile['risks'][row_index % len(profile['risks'])]}"
                for cell in row:
                    set_cell_margins(cell)
                    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
                    for p in cell.paragraphs:
                        for run in p.runs:
                            run.font.name = "Microsoft YaHei"
                            run.font.size = Pt(8.5)
    document.save(output)
    normalize_ooxml(output)


def normalize_ooxml(path: Path) -> None:
    temp = path.with_suffix(path.suffix + ".normalized")
    with zipfile.ZipFile(path, "r") as source, zipfile.ZipFile(temp, "w", zipfile.ZIP_DEFLATED, compresslevel=9) as target:
        for name in sorted(source.namelist()):
            data = source.read(name)
            if name == "docProps/core.xml":
                # openpyxl refreshes this property at save time. Canonicalize it
                # together with ZIP member timestamps so the fixed seed produces
                # byte-identical OOXML artifacts on every run.
                data = re.sub(
                    rb"(<dcterms:modified\b[^>]*>).*?(</dcterms:modified>)",
                    rb"\g<1>2026-09-17T00:00:00Z\g<2>",
                    data,
                )
            info = zipfile.ZipInfo(name, ZIP_TIME)
            info.compress_type = zipfile.ZIP_DEFLATED
            info.external_attr = source.getinfo(name).external_attr
            info.create_system = 0
            target.writestr(info, data)
    temp.replace(path)


def workbook_common(doc: DocumentSpec) -> Workbook:
    wb = Workbook()
    wb.remove(wb.active)
    wb.properties.creator = COMPANY
    wb.properties.lastModifiedBy = COMPANY
    wb.properties.created = FIXED_TIME.replace(tzinfo=None)
    wb.properties.modified = FIXED_TIME.replace(tzinfo=None)
    wb.properties.title = doc.title
    wb.calculation.fullCalcOnLoad = True
    return wb


def add_structured_sheet(wb: Workbook, doc: DocumentSpec, title: str, headers: list[str], rows: list[list]) -> None:
    ws = wb.create_sheet(title)
    ws.sheet_view.showGridLines = False
    ws["A1"] = doc.title
    ws["A2"] = f"文档ID：{doc.document_id}    版本：{doc.version}    归口部门：{doc.department}    生效日：{doc.effective_date}"
    ws.merge_cells(start_row=1, start_column=1, end_row=1, end_column=len(headers))
    ws.merge_cells(start_row=2, start_column=1, end_row=2, end_column=len(headers))
    ws["A1"].font = Font(name="Microsoft YaHei", size=15, bold=True, color="000000")
    ws["A2"].font = Font(name="Microsoft YaHei", size=9, italic=True, color="666666")
    ws.append([])
    ws.append(headers)
    for row in rows:
        ws.append(row)
    header_row = 4
    end_row = header_row + len(rows)
    end_col = len(headers)
    table = Table(displayName=f"T{doc.document_id.replace('-', '')}{len(wb.sheetnames):02d}",
                  ref=f"A{header_row}:{get_column_letter(end_col)}{end_row}")
    table.tableStyleInfo = TableStyleInfo(name="TableStyleMedium2", showFirstColumn=False,
                                          showLastColumn=False, showRowStripes=True, showColumnStripes=False)
    ws.add_table(table)
    dark = PatternFill("solid", fgColor="203864")
    border = Border(left=Side(style="thin", color="D9D9D9"), right=Side(style="thin", color="D9D9D9"),
                    top=Side(style="thin", color="D9D9D9"), bottom=Side(style="thin", color="D9D9D9"))
    for cell in ws[header_row]:
        cell.fill = dark
        cell.font = Font(name="Microsoft YaHei", size=10, bold=True, color="FFFFFF")
        cell.alignment = Alignment(horizontal="center", vertical="center", wrap_text=True)
        cell.border = border
    for row in ws.iter_rows(min_row=header_row + 1, max_row=end_row, max_col=end_col):
        for cell in row:
            cell.font = Font(name="Microsoft YaHei", size=10)
            cell.alignment = Alignment(horizontal="left" if isinstance(cell.value, str) else "right",
                                       vertical="center", wrap_text=True)
            cell.border = border
    ws.freeze_panes = "A5"
    for index, header in enumerate(headers, 1):
        width = max(12, min(34, max(len(str(header)) + 4, *(len(str(row[index - 1])) + 2 for row in rows))))
        ws.column_dimensions[get_column_letter(index)].width = width
    ws.row_dimensions[1].height = 26
    ws.row_dimensions[2].height = 22
    ws.row_dimensions[4].height = 28


def write_xlsx(doc: DocumentSpec, output: Path, facts: list[dict]) -> None:
    wb = workbook_common(doc)
    if doc.document_id == "FIN-002":
        add_structured_sheet(wb, doc, "城市等级", ["城市", "等级", "适用说明"], [
            ["上海", "A", "核心商务城市；FIN-004：上海属于A类城市"], ["北京", "A", "核心商务城市"],
            ["深圳", "A", "核心商务城市"], ["广州", "B", "重点城市"], ["成都", "B", "重点城市"],
            ["西安", "B", "重点城市"], ["其他国内城市", "C", "未单列城市"],
        ])
        add_structured_sheet(wb, doc, "国内住宿标准", ["职级", "A类 元/晚", "B类 元/晚", "C类 元/晚", "说明"], [
            ["P1-P5", 500, 420, 350, "按实际发生额与上限孰低"],
            ["P6-P7", 600, 500, 420, "FIN-005：P6/P7上海住宿标准600元/晚"],
            ["P8-P9", 750, 650, 550, "按差旅申请核定"], ["VP及以上", 900, 800, 700, "按批准行程执行"],
        ])
        add_structured_sheet(wb, doc, "餐费标准", ["范围", "标准 元/自然日", "计算口径", "说明"], [
            ["国内一般地区", 120, "自然日", "FIN-011：国内差旅餐补按自然日计算"],
            ["国内特殊区域", 160, "自然日", "以特殊区域表为准"],
        ])
        add_structured_sheet(wb, doc, "海外地区", ["国家或地区组", "住宿币种", "住宿上限", "餐费上限", "说明"], [
            ["亚洲I组", "USD", 180, 65, "FIN-017：海外住宿按国家/地区标准表执行"],
            ["欧洲I组", "EUR", 210, 75, "具体城市按出差申请"], ["北美I组", "USD", 230, 80, "具体城市按出差申请"],
        ])
        add_structured_sheet(wb, doc, "特殊区域", ["区域", "附加上限 元/晚", "适用条件", "审批"], [
            ["高原地区", 100, "连续住宿且无协议酒店", "部门负责人"], ["偏远项目地", 120, "项目确认交通受限", "部门负责人+财务"],
        ])
    elif doc.document_id == "SEC-002":
        add_structured_sheet(wb, doc, "分类等级", ["等级", "定义", "影响", "示例"], [
            ["L1", "公开数据", "泄露影响较低", "官网公开信息；SEC-001：数据分L1/L2/L3/L4"], ["L2", "内部数据", "影响内部运营", "员工通讯录"],
            ["L3", "敏感数据", "可能造成客户或公司损失", "未公开财务、客户身份证"],
            ["L4", "核心敏感数据", "可能造成重大业务或安全影响", "生产数据库密钥"],
        ])
        add_structured_sheet(wb, doc, "数据示例", ["数据项", "等级", "所有者", "依据说明"], [
            ["员工通讯录", "L2", "HR", "SEC-002：员工通讯录属于L2"],
            ["未公开财务数据", "L3", "Finance", "SEC-003：未公开财务数据属于L3"],
            ["客户身份证", "L3", "Business Owner", "SEC-004：客户身份证数据属于L3"],
            ["生产数据库密钥", "L4", "System Owner", "SEC-005：生产数据库密钥属于L4"],
        ])
        add_structured_sheet(wb, doc, "存储与传输", ["等级", "允许存储", "传输规则", "外发"], [
            ["L1", "公共内容平台", "可公开传输", "允许"], ["L2", "公司受控协作空间", "公司账号传输", "审批后"],
            ["L3", "受控存储", "加密通道", "数据所有者审批"], ["L4", "专用受控存储", "强加密专用通道", "原则上禁止"],
        ])
        add_structured_sheet(wb, doc, "加密要求", ["等级", "静态加密", "传输加密", "访问控制", "说明"], [
            ["L1", "可选", "可选", "公开", ""], ["L2", "平台默认", "公司通道", "员工身份", ""],
            ["L3", "必须", "必须", "最小权限", "SEC-006：L3/L4数据要求受控存储和加密"],
            ["L4", "必须且密钥分离", "必须", "双人审批", "SEC-006：L3/L4数据要求受控存储和加密"],
        ])
    elif doc.document_id == "PROC-003":
        add_structured_sheet(wb, doc, "审批矩阵", ["金额下限 元", "金额上限 元", "边界", "审批角色", "说明"], [
            [0, 50000, "≤5万元", "部门负责人", "PROC-001：≤5万元采购仅部门负责人审批"],
            [50000.01, 200000, "5~20万元", "部门负责人+VP", "PROC-002：5~20万元增加VP审批"],
            [200000.01, 1000000, "20~100万元", "部门负责人+VP+CFO", "PROC-003：20~100万元增加CFO审批"],
            [1000000.01, None, ">100万元", "部门负责人+VP+CFO+CEO", "PROC-004：>100万元增加CEO审批"],
        ])
        add_structured_sheet(wb, doc, "边界示例", ["采购金额 元", "审批链", "判定说明"], [
            [50000, "部门负责人", "五万元整归入≤5万元"], [200000, "部门负责人+VP", "二十万元整归入5~20万元"],
            [850000, "部门负责人+VP+CFO", "PROC-005：85万元软件采购需负责人+VP+CFO"],
            [1000000, "部门负责人+VP+CFO", "一百万元整归入20~100万元"],
            [1000001, "部门负责人+VP+CFO+CEO", "超过一百万元增加CEO"],
        ])
        add_structured_sheet(wb, doc, "适用说明", ["主题", "规则", "不替代事项"], [
            ["采购审批", "按采购总金额适用矩阵", "不替代预算、供应商准入和合同审核"],
            ["禁止拆分", "同一目的采购不得拆分规避审批", "不改变合理分批交付"],
            ["流程边界", "采购审批、合同签署权限、供应商准入、付款审批和单一来源例外分别留痕", "任一完成均不代表其他环节自动通过"],
        ])
    elif doc.document_id == "OPS-003":
        add_structured_sheet(wb, doc, "事故等级", ["等级", "定义", "业务示例", "排除示例"], [
            ["P0", "P0=核心业务整体不可用", "核心支付整体不可用属于P0", "数据库慢但核心业务仍可用"],
            ["P1", "重要功能严重受损或大范围降级", "主要查询持续失败", "单用户问题"],
            ["P2", "局部功能受影响且有替代路径", "单一区域非核心功能异常", "咨询类工单"],
        ])
        add_structured_sheet(wb, doc, "响应时限", ["等级", "首次响应 分钟", "技术负责人介入 分钟", "说明"], [
            ["P0", 5, 10, "P0首次响应≤5分钟；P0技术负责人≤10分钟介入"],
            ["P1", 10, 20, "OPS-005：P1首次响应≤10分钟"], ["P2", 30, 60, "按值班队列处理"],
        ])
        add_structured_sheet(wb, doc, "升级与沟通", ["等级", "状态同步频率 分钟", "指挥角色", "必要参与"], [
            ["P0", 15, "Incident Commander", "技术负责人、Business Owner；P0每15分钟同步状态"],
            ["P1", 30, "事件负责人", "相关服务Owner"], ["P2", 60, "处理人", "值班负责人"],
        ])
        add_structured_sheet(wb, doc, "判定示例", ["场景", "建议等级", "理由"], [
            ["核心支付整体不可用", "P0", "OPS-021：属于P0"],
            ["数据库响应变慢但核心交易可完成", "视影响评估，不当然为P0", "OPS-020：不当然属于P0"],
            ["单个员工无法登录", "P2或普通工单", "未构成核心业务整体不可用"],
        ])
    else:
        raise ValueError(doc.document_id)
    wb.save(output)
    normalize_ooxml(output)


def faq_topics(doc: DocumentSpec) -> tuple[list[str], list[str], list[str]]:
    base = list(doc.sections)
    if doc.document_id == "HR-005":
        return base, ["员工自助平台", "HRBP", "直属负责人"], ["HR-001", "HR-003", "HR-004"]
    if doc.document_id == "FIN-005":
        return base, ["费用系统", "电子票夹", "财务共享中心"], ["FIN-001", "FIN-002", "FIN-004"]
    if doc.document_id == "SEC-005":
        return base, ["SOC工单", "Security On-call", "服务台"], ["SEC-001", "SEC-002", "SEC-003", "SEC-004"]
    if doc.document_id == "LEG-002":
        return base, ["采购平台", "合同管理平台", "法务审核人"], ["PROC-001", "PROC-002", "PROC-003", "LEG-001"]
    return base, ["变更平台", "值班系统", "Incident Commander"], ["ENG-001", "OPS-001", "OPS-002", "OPS-003"]


def write_markdown(doc: DocumentSpec, output: Path, facts: list[dict]) -> None:
    topics, channels, refs = faq_topics(doc)
    lines = [f"# {doc.title}", "", f"- 文档ID：{doc.document_id}", f"- 版本：{doc.version}",
             f"- 归口部门：{doc.department}", f"- 发布日期：{doc.publish_date}", f"- 生效日期：{doc.effective_date}", "",
             "本指南用于快速定位办理入口和正式制度。遇到金额、时限、权限或版本冲突时，应回到所引用的正式文件确认完整条件。", ""]
    variants = [
        "我现在遇到{topic}，第一步应该做什么？", "{topic}卡住了，需要先找谁确认？", "临时发生{topic}，能不能先处理后补材料？",
        "{topic}的记录在哪里提交？", "同事给了另一种{topic}说法，应该以什么为准？", "{topic}已经完成，还需要保留哪些记录？",
        "办理{topic}时系统提示信息不全，接下来怎么办？", "{topic}涉及其他部门时，怎样避免重复申请？", "关于{topic}，员工最容易混淆的边界是什么？",
    ]
    fact_cursor = 0
    for index in range(1, doc.faq_count + 1):
        topic = topics[(index - 1) % len(topics)]
        if index == 1 or topic != topics[(index - 2) % len(topics)]:
            lines.extend([f"## {topic}", ""])
        question = variants[(index - 1) % len(variants)].format(topic=topic)
        channel = channels[(index * 3) % len(channels)]
        reference = refs[(index * 5) % len(refs)]
        if fact_cursor < len(facts):
            answer = (f"{facts[fact_cursor]['statement']}。请在{channel}提交与场景对应的材料，并以 {reference} 的完整条款为准；"
                      "FAQ只说明办理方向，不替代正式审批和有效版本判断。")
            fact_cursor += 1
        else:
            answer = (f"先在{channel}确认适用对象、发生时间和当前状态，再按 {reference} 定位正式规则。"
                      f"如果{topic}同时涉及金额、数据等级、生产权限或紧急处置，应分别取得责任部门结论，不要用口头答复替代系统记录。"
                      "材料暂时不全时可以先咨询，但提交与批准的先后顺序仍按正式制度执行。")
        lines.extend([f"### Q{index:03d} {question}", "", answer, ""])
    output.write_text("\n".join(lines), encoding="utf-8", newline="\n")


def expected_documents_for(fact_ids: list[str], facts_by_id: dict[str, dict], category: str) -> list[str]:
    result = []
    for fact_id in fact_ids:
        for source in facts_by_id[fact_id]["primarySources"]:
            if source not in result:
                result.append(source)
    if category == "VERSION_CONFLICT":
        forbidden = {item for fact_id in fact_ids for item in VERSION_FORBIDDEN.get(fact_id, [])}
        current = [doc for doc in result if doc not in forbidden]
        return current or result
    return result


def build_questions(facts: list[dict]) -> tuple[list[dict], list[dict]]:
    facts_by_id = {fact["factId"]: fact for fact in facts}
    docs_by_id = {doc.document_id: doc for doc in DOCUMENTS}
    questions = []
    evidence = []
    codes = {"SEMANTIC": "SEM", "KEYWORD_BM25": "BM25", "HYBRID": "HYB",
             "CROSS_DOCUMENT": "XDO", "VERSION_CONFLICT": "VER", "TABLE": "TAB",
             "STRUCTURE_AGENTIC": "STR"}
    for category, entries in QUESTION_SETS.items():
        if len(entries) != CATEGORY_COUNTS[category]:
            raise ValueError(f"{category} question definition count {len(entries)} != {CATEGORY_COUNTS[category]}")
        for number, (query, fact_ids) in enumerate(entries, 1):
            missing = [fact_id for fact_id in fact_ids if fact_id not in facts_by_id]
            if missing:
                raise ValueError(f"Question facts missing: {missing}")
            expected_docs = expected_documents_for(fact_ids, facts_by_id, category)
            section_count = 3 if category == "STRUCTURE_AGENTIC" else 2
            expected_sections = []
            for doc_id in expected_docs:
                for section in docs_by_id[doc_id].sections[:section_count]:
                    label = f"{doc_id}/{section}"
                    if label not in expected_sections:
                        expected_sections.append(label)
            required = [facts_by_id[fact_id]["statement"] for fact_id in fact_ids]
            prefix = fact_ids[0].split("-", 1)[0]
            hard_negatives = [doc for doc in HARD_NEGATIVES[prefix] if doc not in expected_docs][:3]
            forbidden_evidence = []
            forbidden_docs = []
            if category == "VERSION_CONFLICT":
                forbidden_docs = sorted({doc for fact_id in fact_ids for doc in VERSION_FORBIDDEN.get(fact_id, [])})
                forbidden_evidence = [{"documentId": doc_id, "fact": "不得以已废止或不适用时段的规则覆盖当前有效口径"}
                                      for doc_id in forbidden_docs]
                if not forbidden_evidence:
                    forbidden_evidence = [{"documentId": "FIN-003", "fact": "不得在通知有效期外套用临时800元标准"}]
                    forbidden_docs = ["FIN-003"]
            question_id = f"EVAL-{codes[category]}-{number:03d}"
            difficulty = "HARD" if category in {"CROSS_DOCUMENT", "VERSION_CONFLICT", "STRUCTURE_AGENTIC"} else (
                "MEDIUM" if category in {"HYBRID", "TABLE"} else "EASY")
            expected_answer = "；".join(required)
            questions.append({
                "id": question_id,
                "query": query,
                "category": category,
                "difficulty": difficulty,
                "factIds": fact_ids,
                "expectedAnswer": expected_answer,
                "expectedDocuments": expected_docs,
                "expectedSections": expected_sections,
                "requiredEvidence": required,
                "forbiddenEvidence": forbidden_evidence,
                "hardNegatives": hard_negatives,
                "notes": "需按有效版本、适用条件与证据完整性判断。",
            })
            evidence.append({
                "questionId": question_id,
                "factIds": fact_ids,
                "requiredDocumentIds": expected_docs,
                "requiredSectionPaths": expected_sections,
                "requiredStatements": required,
                "forbiddenDocumentIds": forbidden_docs,
                "minimumEvidenceCount": max(2 if category == "CROSS_DOCUMENT" else 1, len(required)),
                "requiresAllEvidence": category == "CROSS_DOCUMENT" or len(required) > 1,
                "retrievalGroundTruth": {"documentRecallRequired": True, "sectionRecallRequired": True,
                                         "retrievalUnitRecallRequired": True, "rejectStaleDocuments": True},
                "answerGroundTruth": {"factualCorrectness": True, "faithfulness": True,
                                      "unsupportedClaimsAllowed": False},
            })
    return questions, evidence


def write_json(path: Path, value) -> None:
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, sort_keys=True) + "\n", encoding="utf-8", newline="\n")


def write_jsonl(path: Path, values: list[dict]) -> None:
    with path.open("w", encoding="utf-8", newline="\n") as stream:
        for value in values:
            stream.write(json.dumps(value, ensure_ascii=False, sort_keys=True, separators=(",", ":")) + "\n")


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def prepare_output(root: Path, clean: bool) -> None:
    if clean:
        for name in ("corpus", "evaluation", "metadata"):
            target = root / name
            if target.exists():
                shutil.rmtree(target)
    for folder in ("hr", "finance", "it", "security", "procurement", "legal", "engineering"):
        (root / "corpus" / folder).mkdir(parents=True, exist_ok=True)
    (root / "evaluation").mkdir(parents=True, exist_ok=True)
    (root / "metadata").mkdir(parents=True, exist_ok=True)


def generate(root: Path, spec_path: Path, clean: bool) -> dict:
    if not spec_path.is_file():
        raise FileNotFoundError(spec_path)
    prepare_output(root, clean)
    facts = parse_fact_registry(spec_path)
    by_document = facts_by_document(facts)
    manifest_docs = []
    for doc in DOCUMENTS:
        output = root / "corpus" / doc.folder / filename_for(doc)
        if doc.fmt == "PDF":
            write_pdf(doc, output, by_document[doc.document_id])
        elif doc.fmt == "DOCX":
            write_docx(doc, output, by_document[doc.document_id])
        elif doc.fmt == "XLSX":
            write_xlsx(doc, output, by_document[doc.document_id])
        elif doc.fmt == "MD":
            write_markdown(doc, output, by_document[doc.document_id])
        else:
            raise ValueError(doc.fmt)
        manifest_docs.append({
            "documentId": doc.document_id,
            "documentCode": document_code(doc),
            "title": doc.title,
            "filename": str(output.relative_to(root)).replace("\\", "/"),
            "format": doc.fmt,
            "department": doc.department,
            "version": doc.version,
            "status": doc.status,
            "confidentiality": "INTERNAL",
            "ownerDepartment": doc.department,
            "author": f"{doc.department}制度运营组",
            "reviewer": "内控与合规评审组",
            "approver": f"{doc.department}负责人",
            "publishDate": doc.publish_date,
            "effectiveDate": doc.effective_date,
            "supersedes": supersedes_for(doc.document_id),
            "references": references_for(doc.document_id),
            "revisionHistory": [{"version": doc.version, "date": doc.publish_date, "summary": "按年度治理计划发布"}],
            "factIds": [fact["factId"] for fact in by_document[doc.document_id]],
            "evaluationTags": list(doc.evaluation_tags),
            "pageTarget": doc.page_target,
            "faqCount": doc.faq_count or None,
            "sha256": sha256(output),
            "bytes": output.stat().st_size,
        })

    questions, expected_evidence = build_questions(facts)
    unanswerable = [{"id": f"UNANS-{index:03d}", "query": query, "reasonUnanswerable": reason,
                     "confusingDocuments": list(documents), "expectedBehavior": "INSUFFICIENT_EVIDENCE"}
                    for index, (query, reason, documents) in enumerate(UNANSWERABLE, 1)]
    relations = [{"from": source, "type": relation, "to": target, **({"effectiveRange": effective} if effective else {})}
                 for source, relation, target, effective in RELATIONS]
    version_graph = {
        "specVersion": SPEC_VERSION,
        "nodes": [{"documentId": doc.document_id, "version": doc.version, "status": doc.status,
                   "effectiveDate": doc.effective_date} for doc in DOCUMENTS],
        "edges": [relation for relation in relations if relation["type"] in {"SUPERSEDES", "OVERRIDES"}],
    }
    categories = {"total": sum(CATEGORY_COUNTS.values()), "counts": CATEGORY_COUNTS,
                  "definitions": {
                      "SEMANTIC": "自然语言意图与制度语义",
                      "KEYWORD_BM25": "错误码、制度编号与精确标识符",
                      "HYBRID": "关键词条件与语义场景共同约束",
                      "CROSS_DOCUMENT": "需要多个文档或独立证据共同完成",
                      "VERSION_CONFLICT": "区分当前、历史与临时覆盖规则",
                      "TABLE": "核心事实必须从XLSX结构化表格取得",
                      "STRUCTURE_AGENTIC": "在长文档多个章节间导航与组合",
                  }}
    write_jsonl(root / "evaluation" / "questions.jsonl", questions)
    write_jsonl(root / "evaluation" / "expected_evidence.jsonl", expected_evidence)
    write_jsonl(root / "evaluation" / "unanswerable.jsonl", unanswerable)
    write_json(root / "evaluation" / "categories.json", categories)
    write_json(root / "metadata" / "fact_registry.json", {"specVersion": SPEC_VERSION, "facts": facts})
    write_json(root / "metadata" / "document_relations.json", {"specVersion": SPEC_VERSION, "relations": relations})
    write_json(root / "metadata" / "version_graph.json", version_graph)
    manifest = {"schemaVersion": 1, "specVersion": SPEC_VERSION, "seed": SEED,
                "generatedAt": FIXED_TIME.isoformat().replace("+00:00", "Z"), "company": COMPANY,
                "documentCount": len(manifest_docs), "factCount": len(facts),
                "questionCount": len(questions), "unanswerableCount": len(unanswerable),
                "documents": manifest_docs}
    write_json(root / "metadata" / "manifest.json", manifest)
    return manifest


def main() -> int:
    options = parse_args()
    if options.seed != SEED:
        raise SystemExit(f"Spec v1.1 generator seed is fixed at {SEED}; received {options.seed}")
    manifest = generate(options.output_root.resolve(), options.spec.resolve(), options.clean_generated)
    print(json.dumps({"outputRoot": str(options.output_root.resolve()), "documents": manifest["documentCount"],
                      "facts": manifest["factCount"], "questions": manifest["questionCount"],
                      "unanswerable": manifest["unanswerableCount"], "seed": SEED}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
