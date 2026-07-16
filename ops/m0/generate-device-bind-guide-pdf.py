#!/usr/bin/env python3
"""Generate Chinese PDF install guide (ADB Device Owner only)."""

from __future__ import annotations

import sys
from pathlib import Path

from reportlab.lib.pagesizes import A4
from reportlab.lib.styles import ParagraphStyle, getSampleStyleSheet
from reportlab.lib.units import mm
from reportlab.pdfbase import pdfmetrics
from reportlab.pdfbase.cidfonts import UnicodeCIDFont
from reportlab.platypus import Paragraph, SimpleDocTemplate, Spacer

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "矩阵助手-新设备绑定安装指引.pdf"
RELEASE_OUT = ROOT / "agent-releases" / "device-bind-guide.pdf"

PUBLIC_BASE = "https://android.hashhub.com"
AGENT_VERSION = "0.1.7"
AGENT_VERSION_CODE = "8"
DPC_COMPONENT = (
    "com.loanagent.devicecontroller/"
    "com.loanagent.devicecontroller.LoanAgentDeviceAdminReceiver"
)


def p(text: str, style: ParagraphStyle) -> Paragraph:
    return Paragraph(text.replace("\n", "<br/>"), style)


def main() -> int:
    pdfmetrics.registerFont(UnicodeCIDFont("STSong-Light"))

    styles = getSampleStyleSheet()
    title = ParagraphStyle(
        "TitleCN",
        parent=styles["Title"],
        fontName="STSong-Light",
        fontSize=18,
        leading=24,
        textColor="#1B4332",
        spaceAfter=8,
    )
    h2 = ParagraphStyle(
        "H2CN",
        parent=styles["Heading2"],
        fontName="STSong-Light",
        fontSize=13,
        leading=18,
        textColor="#2D6A4F",
        spaceBefore=10,
        spaceAfter=4,
    )
    body = ParagraphStyle(
        "BodyCN",
        parent=styles["BodyText"],
        fontName="STSong-Light",
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
    mono = ParagraphStyle(
        "MonoCN",
        parent=body,
        fontName="Courier",
        fontSize=9,
        leading=12,
        leftIndent=8,
        textColor="#333333",
        spaceAfter=4,
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
            f"版本：Agent {AGENT_VERSION}（versionCode {AGENT_VERSION_CODE}）　"
            f"适用机型：Redmi Note 12 Turbo（23049RAD8C / marble）<br/>"
            f"云端：{PUBLIC_BASE}　开通方式：仅 ADB Device Owner（不支持扫码）　"
            "更新日期：2026-07-16",
            body,
        ),
        p("一、重要限制（请先读）", h2),
        p("- 当前 HyperOS 机型不支持扫码开通 Device Owner，请按本文用电脑 ADB 开通。", bullet),
        p("- 仅支持 Redmi Note 12 Turbo。其他机型会提示「设备不受支持」，不会心跳。", bullet),
        p(
            f"- 必须安装 Agent {AGENT_VERSION}+（与 Device Controller 同签名）。"
            "勿安装名为 agent-debug.apk 的旧包。",
            bullet,
        ),
        p("- Device Owner 前建议恢复出厂，且不要登录小米/谷歌账号，否则 set-device-owner 常失败。", bullet),
        p("- 禁止锁屏由 Device Controller 策略完成；未禁锁屏时灭屏任务易失败。", bullet),
        p("- 不要对「矩阵助手」使用强制停止（force-stop），会清掉无障碍。", bullet),
        p("二、需要准备什么", h2),
        p("- 一台电脑（Mac/Windows/Linux）与手机在同一局域网，或使用 USB 数据线。", bullet),
        p("- 已安装 adb（Android platform-tools）。运维也可在 Docker 内使用 adb。", bullet),
        p("- 运维台账号（用于绑定）；小红书账号密码（装完后登录）。", bullet),
        p("三、下载安装包（HTTPS）", h2),
        p("- 登录运维台 → 左侧「远程升级」。", bullet),
        p("- 下载「新设备绑定安装指引（PDF）」即本文。", bullet),
        p("- 下载 Device Controller（DPC）与最新 Agent APK，或手机/电脑浏览器打开：", bullet),
        p(f"DPC：{PUBLIC_BASE}/downloads/device-controller-latest.apk", mono),
        p(f"Agent：{PUBLIC_BASE}/downloads/agent-latest.apk", mono),
        p(f"本指引：{PUBLIC_BASE}/downloads/device-bind-guide.pdf", mono),
        p("四、手机：恢复出厂并打开调试（首次 checklist）", h2),
        p("□ 备份后恢复出厂；欢迎页只连 Wi‑Fi，不要登录小米/谷歌账号（有账号则无法设 Device Owner）。", bullet),
        p("□ 进入系统后：设置 → 我的设备 → 全部参数 → 连点「MIUI/OS 版本」打开开发者选项。", bullet),
        p("□ 开发者选项打开「USB 调试」。", bullet),
        p(
            "□ HyperOS 必开：「USB 调试（安全设置）」并确认一次。"
            "未开时电脑执行 dpm 会报 Calling identity is not authorized。",
            bullet,
        ),
        p(
            "□ 建议打开「无线调试」：先记下配对用 IP:配对端口 + 配对码，"
            "再记下连接用 IP:调试端口（两个端口不同；配对码不要和地址写在一起搞混）。",
            bullet,
        ),
        p("□ 首次 adb 连接时在手机上点「允许」。", bullet),
        p("五、电脑：安装 DPC 并设为 Device Owner", h2),
        p("运维推荐用仓库脚本（Docker 内 adb；配对码单独参数）：", body),
        p(
            "bash ops/m0/set-device-owner.sh --pair IP:配对端口 --pair-code 配对码 "
            "--connect IP:调试端口 --install-dpc",
            mono,
        ),
        p("或手动：", body),
        p("adb pair IP:配对端口   # 按提示输入配对码", mono),
        p("adb connect IP:调试端口", mono),
        p("adb devices              # 应看到 device", mono),
        p("adb install -r device-controller-latest.apk", mono),
        p(f"adb shell dpm set-device-owner {DPC_COMPONENT}", mono),
        p("□ set-device-owner 成功（或提示 already set 且本 DPC 已是 Owner）。", bullet),
        p(
            "□ 若报 already some accounts：设置里退出并删除全部账号（尤其小米账号）后再试；"
            "或再清机且欢迎页不登录。",
            bullet,
        ),
        p("打开手机上的 Device Controller（设备控制器）：", body),
        p("□ Device Owner = true（若为 false：确认看的是本机、已 set-device-owner 成功）。", bullet),
        p("□ 点「Apply minimum Device Owner policy」（必点，否则不会禁锁屏）。", bullet),
        p(
            "□ Last recovery 含 KEYGUARD_DISABLED"
            "（形如 applied=[LOCK_TASK, KEYGUARD_DISABLED]；不是单独一行标题）。",
            bullet,
        ),
        p("□ 抄下 Enrolled device_id（远程升级推送用；可能与 Agent 的 dev-… 不同）。", bullet),
        p("□ 完成后不要再设密码/指纹锁屏。", bullet),
        p("六、安装矩阵助手 Agent", h2),
        p("adb install -r agent-latest.apk", mono),
        p(
            f"- 打开「矩阵助手」，确认版本约 {AGENT_VERSION}-debug，"
            "设备型号显示 Redmi Note 12 Turbo（已通过），并记下设备 ID（dev-…）。",
            bullet,
        ),
        p("- 点「打开系统无障碍设置」，开启「矩阵助手」无障碍服务。", bullet),
        p("- 启用 Loanagent 输入法，并按提示设为当前输入法（发帖填字需要）。", bullet),
        p("- 电池：忽略电池优化 / 无限制；允许自启动；关闭强力省电。", bullet),
        p(
            "- HyperOS 必做：设置 → 应用 → 矩阵助手 → 其他权限 → 允许「后台弹出界面」。"
            "否则灭屏后无法拉起小红书。",
            bullet,
        ),
        p("可选 ADB（与上一设置等价）：", body),
        p("adb shell appops set com.loanagent.agent 10021 allow", mono),
        p("adb shell appops set com.loanagent.agent 10020 allow", mono),
        p("adb shell appops set com.loanagent.agent 10008 allow", mono),
        p("- 保持矩阵助手运行；通知栏应有「矩阵助手云桥」。约 30 秒内完成首次心跳。", bullet),
        p("七、登录小红书", h2),
        p("- 安装并登录要用的小红书账号。", bullet),
        p("- 首次打开小红书时授予「照片和视频 / 相册」权限（发布选图需要）。", bullet),
        p("- 可回到矩阵助手点「发布素材自检」，确认相册写入与小红书相册权限通过。", bullet),
        p("八、运维台：创建账号并绑定", h2),
        p("- 打开运维台「设备」页（约每 15 秒刷新）。", bullet),
        p("- 在「待绑定设备」找到与手机一致的 dev-…。", bullet),
        p("- 确认：在线、无障碍已就绪、Agent 版本 ≥ " + AGENT_VERSION + "。", bullet),
        p("- 「为新设备创建账号并绑定」：选设备 → 显示名 → 角色 → 创建并绑定。", bullet),
        p("- 绑定成功后主表「运行状态」应为「可用」。", bullet),
        p("九、完成检查清单", h2),
        p("□ 开发者选项已开「USB 调试」+「USB 调试（安全设置）」。", bullet),
        p("□ 手机无小米/谷歌等账号（设 Owner 前）。", bullet),
        p("□ Device Owner = true。", bullet),
        p("□ 已点 Apply policy，Last recovery 含 KEYGUARD_DISABLED（无密码锁屏）。", bullet),
        p("□ Agent 在线 + 无障碍 ENABLED + 云桥通知在。", bullet),
        p("□ 运维台该机「可用」，设备 ID 与手机一致。", bullet),
        p("□ 小红书已登录且相册权限已开。", bullet),
        p("□（建议）灭屏 ≥5 分钟后发一篇测试笔记，无 SCREEN_NOT_READY。", bullet),
        p("十、常见问题", h2),
        p(
            "dpm 报 Calling identity is not authorized → 打开「USB 调试（安全设置）」并确认。<br/>"
            "dpm 报 already some accounts → 退出删除小米账号（或再清机不登录）后再 set-device-owner。<br/>"
            "dpm 报 already set / Device Owner 已是本 DPC → 已成功，去点 Apply policy 即可。<br/>"
            "界面 Device Owner=false → 确认是当前这台机；用 adb shell dpm list-owners 核对。<br/>"
            "看不到 KEYGUARD_DISABLED → 必须点「Apply minimum Device Owner policy」；"
            "看 Last recovery 的 applied=[…]，不是单独标题行。<br/>"
            "无线调试端口变了 → 重新 pair（--pair + --pair-code）再 --connect 新调试端口。<br/>"
            "待绑定一直为空 → 打开矩阵助手、有云桥通知、手机能访问 "
            f"{PUBLIC_BASE}、版本 ≥ {AGENT_VERSION}。<br/>"
            "在线但无障碍未就绪 → 开启无障碍后等 ≤30 秒；勿 force-stop。<br/>"
            "灭屏任务失败 / 拉不起小红书 → 确认已禁锁屏 + 已开「后台弹出界面」。<br/>"
            "远程升级推送无效 → 推送到 Device Controller 的 enrolled id，不是 Agent 的 dev-…。<br/>"
            "重装 Agent 后出现新 dev-… → 正常，需解绑后重新绑定。",
            body,
        ),
        Spacer(1, 8),
        p("文档结束。运维台「遇到问题怎么办」与本文步骤一致。", body),
    ]
    doc.build(story)
    RELEASE_OUT.parent.mkdir(parents=True, exist_ok=True)
    RELEASE_OUT.write_bytes(OUT.read_bytes())
    print(f"Wrote {OUT}", file=sys.stderr)
    print(f"Wrote {RELEASE_OUT}", file=sys.stderr)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
