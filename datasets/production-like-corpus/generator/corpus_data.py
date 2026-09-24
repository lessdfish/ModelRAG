"""Authoritative deterministic data definitions for corpus Spec v1.1."""

from __future__ import annotations

from dataclasses import dataclass


SEED = 20260917
SPEC_VERSION = "1.1"
COMPANY = "NovaTech 星云科技"


@dataclass(frozen=True)
class DocumentSpec:
    document_id: str
    title: str
    fmt: str
    department: str
    folder: str
    version: str
    status: str
    publish_date: str
    effective_date: str
    page_target: int | None
    sections: tuple[str, ...]
    evaluation_tags: tuple[str, ...]
    faq_count: int = 0


DOCUMENTS = (
    DocumentSpec("HR-001", "员工手册 2026", "DOCX", "HR", "hr", "V5.0", "ACTIVE", "2026-01-15", "2026-02-01", 50,
                 ("适用范围与使用说明", "入职", "考勤", "薪酬", "假期", "绩效", "行为规范", "资产与信息保护", "离职", "引用文件与修订记录"),
                 ("ROUTING", "CROSS_DOCUMENT", "VERSION")),
    DocumentSpec("HR-002", "员工手册 2024", "PDF", "HR", "hr", "V3.1", "SUPERSEDED", "2024-01-08", "2024-02-01", 43,
                 ("适用范围", "入职", "考勤", "薪酬", "假期", "绩效", "行为规范", "资产保护", "离职", "历史修订"),
                 ("STALE", "VERSION_CONFLICT")),
    DocumentSpec("HR-003", "员工休假与考勤管理办法", "PDF", "HR", "hr", "V4.0", "ACTIVE", "2026-01-15", "2026-02-01", 33,
                 ("目的与适用范围", "工作时间", "考勤", "迟到早退", "年假", "病假", "婚假", "产假", "陪产假", "育儿假", "丧假", "事假", "申请与审批", "特殊情况", "附录与修订记录"),
                 ("SEMANTIC", "HYBRID", "STRUCTURE")),
    DocumentSpec("HR-004", "薪酬福利与补贴管理制度", "DOCX", "HR", "hr", "V3.0", "ACTIVE", "2026-01-20", "2026-02-01", 28,
                 ("总则", "薪酬结构", "固定薪酬", "奖金", "餐补", "交通补贴", "健康补贴", "异地派驻补助", "税务处理", "例外与申诉", "附录"),
                 ("HARD_NEGATIVE", "VERSION")),
    DocumentSpec("HR-005", "HR常见问题与员工自助指南", "MD", "HR", "hr", "V2.4", "ACTIVE", "2026-02-01", "2026-02-01", None,
                 ("入职与资料", "考勤与补卡", "各类假期", "薪酬与补贴", "绩效", "离职与证明"),
                 ("FAQ", "SEMANTIC", "ROUTING"), 350),

    DocumentSpec("FIN-001", "差旅及业务出行管理办法 2026", "PDF", "FINANCE", "finance", "V3.2", "ACTIVE", "2026-02-10", "2026-03-01", 38,
                 ("总则", "出差申请", "审批", "交通", "住宿", "餐费", "市内交通", "海外差旅", "报销", "特殊情况", "监督与附则"),
                 ("HYBRID", "EXCEPTION", "CROSS_DOCUMENT")),
    DocumentSpec("FIN-002", "2026差旅费用标准", "XLSX", "FINANCE", "finance", "V2.0", "ACTIVE", "2026-02-10", "2026-03-01", None,
                 ("城市等级", "国内住宿标准", "餐费标准", "海外地区", "特殊区域"),
                 ("TABLE", "ROUTING")),
    DocumentSpec("FIN-003", "2026 Nova AI Summit 差旅标准临时调整通知", "DOCX", "FINANCE", "finance", "V1.0", "ACTIVE", "2026-10-20", "2026-11-10", 3,
                 ("通知范围", "临时住宿标准", "有效期", "不受影响的规则", "执行与咨询"),
                 ("OVERRIDE", "VERSION_CONFLICT", "TIME")),
    DocumentSpec("FIN-004", "费用报销管理办法", "PDF", "FINANCE", "finance", "V4.1", "ACTIVE", "2026-02-12", "2026-03-01", 28,
                 ("总则", "报销时限", "发票要求", "电子发票", "丢失发票", "公司卡", "个人垫付", "超标准处理", "审批与支付", "档案与稽核"),
                 ("SEMANTIC", "HARD_NEGATIVE")),
    DocumentSpec("FIN-005", "财务报销 FAQ", "MD", "FINANCE", "finance", "V2.6", "ACTIVE", "2026-03-01", "2026-03-01", None,
                 ("差旅报销", "发票", "公司卡", "个人垫付", "超标准与例外", "付款状态"),
                 ("FAQ", "SEMANTIC"), 200),

    DocumentSpec("IT-001", "IT服务管理与员工使用手册", "PDF", "IT", "it", "V6.0", "ACTIVE", "2026-01-05", "2026-01-15", 60,
                 ("服务台与支持范围", "账号", "邮箱", "SSO", "VPN", "网络", "打印", "软件安装", "GitLab", "Jira", "资产申请", "服务升级与附录"),
                 ("ROUTING", "LARGE_DOCUMENT")),
    DocumentSpec("IT-002", "VPN使用与故障处理手册", "PDF", "IT", "it", "V3.5", "ACTIVE", "2026-01-08", "2026-01-15", 40,
                 ("适用环境", "安装", "MFA", "设备证书", "连接流程", "常见故障", "日志采集", "证书续签", "回滚", "升级与支持"),
                 ("SEMANTIC", "PROCEDURE")),
    DocumentSpec("IT-003", "IT错误码与故障代码参考手册", "PDF", "IT", "it", "V8.2", "ACTIVE", "2026-01-08", "2026-01-15", 70,
                 ("使用说明", "VPN错误码", "SSO错误码", "邮箱错误码", "网络错误码", "终端错误码", "GitLab错误码", "日志字段", "升级索引"),
                 ("KEYWORD_BM25", "EXACT_MATCH")),
    DocumentSpec("IT-004", "账号角色与权限管理规范", "DOCX", "IT", "it", "V4.0", "ACTIVE", "2026-01-10", "2026-01-15", 28,
                 ("总则", "账号生命周期", "OA权限", "GitLab权限", "普通数据库权限", "角色复核", "离职回收", "生产权限边界", "审计与附录"),
                 ("HARD_NEGATIVE", "CROSS_DOCUMENT")),
    DocumentSpec("IT-005", "终端设备与软件安装规范", "PDF", "IT", "it", "V3.3", "ACTIVE", "2026-01-10", "2026-01-15", 23,
                 ("设备领用", "管理员权限", "软件白名单", "USB", "磁盘加密", "设备遗失", "维修", "更换与报废", "审计"),
                 ("KEYWORD_BM25", "CROSS_DOCUMENT")),

    DocumentSpec("SEC-001", "信息安全管理制度", "PDF", "SECURITY", "security", "V5.0", "ACTIVE", "2026-01-12", "2026-02-01", 55,
                 ("治理与范围", "账号安全", "终端设备", "数据保护", "远程办公", "第三方", "安全事件", "访问控制", "日志与审计", "培训与检查", "附录"),
                 ("ROUTING", "CROSS_DOCUMENT")),
    DocumentSpec("SEC-002", "数据分类分级标准", "XLSX", "SECURITY", "security", "V3.1", "ACTIVE", "2026-01-12", "2026-02-01", None,
                 ("分类等级", "数据示例", "存储与传输", "加密要求"),
                 ("TABLE", "CROSS_DOCUMENT")),
    DocumentSpec("SEC-003", "数据泄露与安全事件应急响应预案", "DOCX", "SECURITY", "security", "V3.0", "ACTIVE", "2026-01-12", "2026-02-01", 35,
                 ("目的与范围", "发现", "报告", "隔离", "证据保全", "升级", "调查", "恢复", "复盘", "演练与附录"),
                 ("SEMANTIC", "PROCEDURE")),
    DocumentSpec("SEC-004", "生产环境与敏感数据访问控制规范", "PDF", "SECURITY", "security", "V4.2", "ACTIVE", "2026-01-14", "2026-02-01", 33,
                 ("总则", "访问分类", "只读权限", "写权限", "临时权限", "Break Glass", "DBA职责", "System Owner职责", "自动失效", "审计与复核"),
                 ("HYBRID", "HARD_NEGATIVE")),
    DocumentSpec("SEC-005", "安全事件 FAQ", "MD", "SECURITY", "security", "V2.2", "ACTIVE", "2026-02-01", "2026-02-01", None,
                 ("设备遗失", "U盘与移动介质", "误发数据", "账号异常", "恶意邮件", "事件报告"),
                 ("FAQ", "SEMANTIC"), 175),

    DocumentSpec("PROC-001", "采购管理制度", "PDF", "PROCUREMENT", "procurement", "V4.0", "ACTIVE", "2026-01-18", "2026-02-01", 38,
                 ("总则", "采购申请", "预算", "询价", "比价", "招标", "单一来源", "审批", "验收", "付款", "监督与附录"),
                 ("RULE", "EXCEPTION", "CROSS_DOCUMENT")),
    DocumentSpec("PROC-002", "供应商准入与生命周期管理办法", "DOCX", "PROCUREMENT", "procurement", "V3.0", "ACTIVE", "2026-01-18", "2026-02-01", 28,
                 ("总则", "初审", "资质审核", "安全审查", "财务审查", "准入决定", "黑名单", "年度复核", "整改", "退出与档案"),
                 ("CROSS_DOCUMENT", "HARD_NEGATIVE")),
    DocumentSpec("PROC-003", "采购审批权限矩阵", "XLSX", "PROCUREMENT", "procurement", "V2.1", "ACTIVE", "2026-01-18", "2026-02-01", None,
                 ("审批矩阵", "边界示例", "适用说明"),
                 ("TABLE", "BOUNDARY")),
    DocumentSpec("LEG-001", "合同管理制度", "PDF", "LEGAL", "legal", "V4.3", "ACTIVE", "2026-01-20", "2026-02-01", 33,
                 ("总则", "合同模板", "法务审核", "非标条款", "签署权限", "印章", "归档", "续签", "终止", "争议与附录"),
                 ("HYBRID", "HARD_NEGATIVE")),
    DocumentSpec("LEG-002", "合同与采购常见问题", "MD", "LEGAL", "legal", "V2.0", "ACTIVE", "2026-02-01", "2026-02-01", None,
                 ("采购与合同边界", "供应商准入", "合同审核", "签署与用印", "补签", "续签与终止"),
                 ("FAQ", "CROSS_DOCUMENT"), 175),

    DocumentSpec("ENG-001", "研发项目生命周期管理规范", "PDF", "ENGINEERING", "engineering", "V5.0", "ACTIVE", "2026-01-06", "2026-01-20", 48,
                 ("总则", "需求", "立项", "设计", "开发", "代码审查", "测试", "发布", "验收", "归档", "度量与附录"),
                 ("STRUCTURE_AGENTIC", "LIFECYCLE")),
    DocumentSpec("OPS-001", "生产变更管理规范", "DOCX", "OPERATIONS", "engineering", "V4.5", "ACTIVE", "2026-01-06", "2026-01-20", 38,
                 ("总则", "变更分类", "普通变更", "标准变更", "紧急变更", "风险评估", "审批", "灰度", "回滚", "变更窗口", "失败处理", "复核与附录"),
                 ("HYBRID", "PROCEDURE", "HARD_NEGATIVE")),
    DocumentSpec("OPS-002", "生产事故应急响应手册", "PDF", "OPERATIONS", "engineering", "V6.0", "ACTIVE", "2026-01-06", "2026-01-20", 65,
                 ("总则", "事故等级", "P0响应", "数据库故障", "网络故障", "服务故障", "回滚失败", "指挥体系", "沟通", "恢复", "复盘", "升级矩阵", "附录A 值班角色与升级通讯矩阵"),
                 ("STRUCTURE_AGENTIC", "PROCEDURE", "CROSS_DOCUMENT")),
    DocumentSpec("OPS-003", "服务等级与生产事故等级标准", "XLSX", "OPERATIONS", "engineering", "V3.2", "ACTIVE", "2026-01-06", "2026-01-20", None,
                 ("事故等级", "响应时限", "升级与沟通", "判定示例"),
                 ("TABLE", "SLA")),
    DocumentSpec("OPS-004", "研发与运维 FAQ", "MD", "OPERATIONS", "engineering", "V3.0", "ACTIVE", "2026-01-20", "2026-01-20", None,
                 ("发布与变更", "灰度", "回滚", "事故分级", "值班与升级", "复盘"),
                 ("FAQ", "SEMANTIC", "STRUCTURE_AGENTIC"), 250),
)


RELATIONS = (
    ("HR-001", "REFERENCES", "HR-003", None),
    ("HR-001", "REFERENCES", "HR-004", None),
    ("HR-001", "SUPERSEDES", "HR-002", None),
    ("FIN-001", "REFERENCES", "FIN-002", None),
    ("FIN-001", "RELATED", "FIN-004", None),
    ("FIN-003", "OVERRIDES", "FIN-002", {"from": "2026-11-10", "to": "2026-11-18"}),
    ("IT-001", "REFERENCES", "IT-002", None),
    ("IT-001", "REFERENCES", "IT-003", None),
    ("IT-001", "REFERENCES", "IT-004", None),
    ("SEC-001", "REFERENCES", "SEC-002", None),
    ("SEC-001", "REFERENCES", "SEC-003", None),
    ("SEC-001", "REFERENCES", "SEC-004", None),
    ("PROC-001", "REFERENCES", "PROC-003", None),
    ("PROC-001", "REFERENCES", "PROC-002", None),
    ("PROC-001", "RELATED", "LEG-001", None),
    ("OPS-001", "RELATED", "OPS-002", None),
    ("OPS-002", "REFERENCES", "OPS-003", None),
)


CATEGORY_COUNTS = {
    "SEMANTIC": 35,
    "KEYWORD_BM25": 25,
    "HYBRID": 30,
    "CROSS_DOCUMENT": 20,
    "VERSION_CONFLICT": 15,
    "TABLE": 15,
    "STRUCTURE_AGENTIC": 10,
}


UNANSWERABLE = (
    ("公司2027年的陪产假政策会改成多少天？", "未来政策尚未发布", ("HR-001", "HR-003")),
    ("CEO在2026年度拿到的个人奖金是多少？", "知识库不包含个人薪酬", ("HR-004",)),
    ("11月19日以后上海酒店还会继续按800元报销吗？", "临时通知只覆盖至11月18日", ("FIN-002", "FIN-003")),
    ("员工陈某昨天具体请了哪一种假？", "知识库不包含个人考勤记录", ("HR-003", "HR-005")),
    ("2027年P0首次响应会调整为几分钟？", "未来SLA尚未发布", ("OPS-002", "OPS-003")),
    ("下一版VPN客户端确切发布日期是哪天？", "未提供产品发布计划", ("IT-001", "IT-002")),
    ("某客户身份证表一共包含多少条记录？", "不包含真实客户数据或事件明细", ("SEC-002", "SEC-003")),
    ("今年被列入供应商黑名单的公司有哪些？", "不包含供应商实例名单", ("PROC-002",)),
    ("85万元采购最终由哪位CFO本人签字？", "制度仅规定角色，不含具体审批实例", ("PROC-003",)),
    ("支付系统下次P0事故会发生在什么时候？", "无法从制度预测未来事故", ("OPS-002", "OPS-003")),
    ("某员工GitLab里具体加入了哪些项目？", "不包含个人权限实例", ("IT-004",)),
    ("生产数据库当前口令是什么？", "制度不得包含生产密钥", ("SEC-002", "SEC-004")),
    ("上个月公司卡一共刷了多少钱？", "不包含交易流水", ("FIN-004", "FIN-005")),
    ("明年A类城市住宿标准会涨到多少？", "未来标准尚未发布", ("FIN-002",)),
    ("最近一次Break Glass是谁发起的？", "不包含访问审计实例", ("SEC-004",)),
    ("本周Network On-call的真人姓名和手机号是什么？", "手册仅使用角色名", ("OPS-002",)),
    ("某项目今天是否已经通过验收？", "不包含项目执行状态", ("ENG-001",)),
    ("公司正在谈判的合同金额是多少？", "不包含具体合同商业信息", ("LEG-001", "LEG-002")),
    ("下季度员工健康补贴是否提高？", "未来补贴调整尚未发布", ("HR-002", "HR-004")),
    ("某次发票重复报销是谁操作的？", "不包含个人报销案件", ("FIN-004", "FIN-005")),
)


HARD_NEGATIVES = {
    "HR": ("HR-002", "HR-003", "HR-004", "FIN-001", "FIN-002"),
    "FIN": ("HR-004", "FIN-001", "FIN-002", "FIN-003", "FIN-004"),
    "IT": ("IT-004", "IT-005", "SEC-002", "SEC-004", "PROC-003"),
    "SEC": ("IT-004", "IT-005", "SEC-002", "SEC-003", "SEC-004"),
    "PROC": ("PROC-001", "PROC-002", "PROC-003", "LEG-001", "LEG-002"),
    "LEG": ("PROC-001", "PROC-002", "PROC-003", "LEG-001", "LEG-002"),
    "OPS": ("ENG-001", "OPS-001", "OPS-002", "OPS-003", "OPS-004"),
    "ENG": ("ENG-001", "OPS-001", "OPS-002", "OPS-003", "OPS-004"),
}

