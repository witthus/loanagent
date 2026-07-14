#!/usr/bin/env python3
"""Generate Chinese PDF install guide with reportlab + CJK TTF."""

from __future__ import annotations

import sys
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.ttfonts import TTFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "矩阵助手-新设备绑定安装指引.pdf"
RELEASE_OUT = ROOT / "agent-releases" / "device-bind-guide.pdf"
FONT_CANDIDATES = [
    Path(__file__).resolve().parent / "fonts" / "ArialUnicode.ttf",
    Path("/workspace/ops/m0/fonts/ArialUnicode.ttf"),
]


def pick_font() -> Path:
    for path in FONT_CANDIDATES:
        if path.is_file():
            return path
    raise SystemExit(f"Missing CJK font. Tried: {FONT_CANDIDATES}")


def p(text: str, style: ParagraphStyle) -> Paragraph:
    return Paragraph(text.replace("\n", "<br/>"), style)


def main() -> int:
    font_path = pick_font()
    pdfmetrics.registerFont(TTFont("CJK", str(font_path)))

    styles = getSampleStyleSheet()
    title = ParagraphStyle(
        "TitleCN",
        parent=styles["Title"],
        fontName="CJK",
        fontSize=18,
        leading=24,
        textColor="#1B4332",
        spaceAfter=8,
    )
    h2 = ParagraphStyle(
        "H2CN",
        parent=styles["Heading2"],
        fontName="CJK",
        fontSize=13,
        leading=18,
        textColor="#2D6A4F",
        spaceBefore=10,
        spaceAfter=4,
    )
    body = ParagraphStyle(
        "BodyCN",
        parent=styles["BodyText"],
        fontName="CJK",
        fontSize=10.5,
        leading=16,
        textColor="#1F2329",
        spaceAfter=4,
    )
    bullet = ParagraphStyle(
        "BulletCN",
        parent=body,
        leftIndent=12,
        bulletIndent=0,
        spaceAfter=3,
    )

    doc = SimpleDocTemplate(
        str(OUT),
        pagesize=A4,
        leftMargin=18 * mm,
        rightMargin=18 * mm,
        topMargin=16 * mm,
        bottomMargin=16 * mm,
        title="矩阵助手新设备绑定安装指引",
    )
    story = [
        p("矩阵助手 · 新设备绑定安装指引", title),
        p(
            "版本：Agent 0.1.2+　适用机型：仅 Redmi Note 12 Turbo（型号 23049RAD8C / 代号 marble）<br/>"
            "云端地址：http://119.45.36.208　更新日期：2026-07-14",
            body,
        ),
        p("一、重要限制（请先读）", h2),
        p("- 目前仅支持 Redmi Note 12 Turbo。其他手机安装后会在 App 内提示「设备不受支持」，不会连接云端心跳。", bullet),
        p("- 必须安装 Agent 0.1.2 及以上。旧版会把所有手机显示成同一台 redmi-note-12，无法多机部署。", bullet),
        p("- 仅安装不打开 App，云端不会出现设备。打开矩阵助手或开启无障碍后，约 30 秒内首次心跳。", bullet),
        p("- 卸载重装（清除应用数据）会生成新的设备 ID（dev-…），需要重新绑定账号。", bullet),
        p("二、运维台：下载安装包", h2),
        p("- 登录运维台 → 左侧「设备」。", bullet),
        p("- 在「安装矩阵助手 Agent」确认版本为 0.1.2。", bullet),
        p("- 点击「下载最新 Agent APK」，或用手机浏览器打开：http://119.45.36.208/downloads/agent-latest.apk", bullet),
        p(
            "- 安装指引 PDF 也可直接下载：http://119.45.36.208/downloads/device-bind-guide.pdf",
            bullet,
        ),
        p("- 允许「未知来源安装」，完成安装。", bullet),
        p("三、新手机操作", h2),
        p("- 打开「矩阵助手」。", bullet),
        p("- 确认状态页显示「设备型号：Redmi Note 12 Turbo（已通过）」。若提示不受支持，请更换机型。", bullet),
        p("- 记下「设备 ID」（形如 dev-xxxxxxxx）。若仍是 redmi-note-12，说明是旧包，请卸载后重装 0.1.2+。", bullet),
        p("- 点击「打开系统无障碍设置」，开启「矩阵助手」无障碍服务。", bullet),
        p("- 返回 App，点「刷新状态」，确认无障碍为 ENABLED。", bullet),
        p("- 保持应用运行；通知栏应出现「矩阵助手云桥」。HyperOS 请关闭对该应用的强力省电限制。", bullet),
        p("四、运维台：创建账号并绑定", h2),
        p("- 停留在「设备」页（每 15 秒自动刷新）。", bullet),
        p("- 在橙色「待绑定设备」中找到与手机一致的 dev-… 行。", bullet),
        p("- 确认该行：在线、无障碍已就绪、Agent 为 0.1.2-debug（或更新）。", bullet),
        p("- 「为新设备创建账号并绑定」：选择在线设备 → 填写显示名 → 选择角色 → 创建并绑定。", bullet),
        p("- 绑定成功后，主表「运行状态」应为「可用」。", bullet),
        p("五、旧机升级 / 换机（redmi-note-12 → dev-*）", h2),
        p("- 升级到 0.1.2+ 后会出现新的待绑定设备 ID。", bullet),
        p("- 对旧账号点「解绑」，再绑到新设备；或使用「绑定已有账号」。", bullet),
        p("- 一台手机同时只能绑定一个账号。确认「可用」后再下发任务。", bullet),
        p("六、完成检查清单", h2),
        p("- 手机为 Redmi Note 12 Turbo，状态页显示型号已通过。", bullet),
        p("- 手机设备 ID 与运维台待绑定/主表一致。", bullet),
        p("- 在线 + 无障碍已就绪 + Agent ≥ 0.1.2。", bullet),
        p("- 主表运行状态：可用。", bullet),
        p("- 该机已登录对应小红书账号（发帖/私信需要）。", bullet),
        p("七、常见问题", h2),
        p(
            "待绑定一直为空 → 确认机型、已打开 App、有云桥通知、能访问 119.45.36.208、版本 ≥ 0.1.2。<br/>"
            "App 提示不受支持 → 换 Redmi Note 12 Turbo。<br/>"
            "在线但无障碍未就绪 → 开启无障碍后等待 ≤30 秒。<br/>"
            "绑定报「还找不到这台手机」→ 尚未心跳成功，等 30–60 秒重试。<br/>"
            "绑定成功但任务失败「辅助功能还没就绪」→ 无障碍未开或应用被杀掉。<br/>"
            "多台手机只出现 redmi-note-12 → 旧包，全部重装 0.1.2+。<br/>"
            "重装后出现新 dev-… → 正常，需重新绑定账号。",
            body,
        ),
        Spacer(1, 8),
        p("文档结束。如有问题，请在运维台「遇到问题怎么办」查看同步说明。", body),
    ]
    doc.build(story)
    RELEASE_OUT.parent.mkdir(parents=True, exist_ok=True)
    RELEASE_OUT.write_bytes(OUT.read_bytes())
    print(f"Wrote {OUT}", file=sys.stderr)
    print(f"Wrote {RELEASE_OUT}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
